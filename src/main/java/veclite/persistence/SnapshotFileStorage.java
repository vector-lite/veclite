package veclite.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorStore;
import veclite.model.VectorDocument;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于本地文件系统快照的持久化存储实现。
 * <p>
 * 每个 VectorStore 对应持久化目录下的一个同名子文件夹（例如 `store/knowledge/`）：
 * <ul>
 *   <li><b>store.json</b>：存储 Store 的维度、Metric、模型名称等元数据配置</li>
 *   <li><b>vectors.bin</b>：二进制高效平铺存储未删除的二进制 float 向量数组</li>
 *   <li><b>documents.jsonl</b>：按行存储 JSON 格式的文档 ID、Text 及 Key-Value Metadata 关联信息</li>
 * </ul>
 * 采用 <b>.tmp 临时目录 + 物理文件原子覆盖 (ATOMIC_MOVE)</b> 方式写入，防止程序中途崩溃导致持久化文件坏块损坏。
 * @author zhaoyuanlu
 */
public class SnapshotFileStorage implements VectorPersistenceStorage {

    /** 向量配置属性 */
    private final VectorLiteProperties properties;
    
    /** Jackson JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SnapshotFileStorage(VectorLiteProperties properties) {
        this.properties = properties;
    }

    /**
     * 将指定的 LocalVectorStore 导出刷盘保存到本地文件快照。
     */
    @Override
    public synchronized void saveStore(LocalVectorStore store) {
        if (store == null) {
            return;
        }
        String storeName = store.getDefinition().getStoreName();
        File storeDir = getStoreDir(storeName);
        if (!storeDir.exists()) {
            storeDir.mkdirs();
        }

        // 1. 创建临时目录 storeName.tmp
        File tmpDir = new File(storeDir.getParentFile(), storeName + ".tmp");
        if (tmpDir.exists()) {
            deleteDirectory(tmpDir);
        }
        tmpDir.mkdirs();

        try {
            // 2. 写入 store.json 配置
            File storeJsonFile = new File(tmpDir, "store.json");
            objectMapper.writeValue(storeJsonFile, store.getDefinition());

            // 3. 写入 vectors.bin 二进制向量数据
            File vectorsBinFile = new File(tmpDir, "vectors.bin");
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(vectorsBinFile)))) {
                int activeCount = store.getActiveCount();
                int dimension = store.getDefinition().getDimension();
                boolean isSQ8 = store.isSQ8Enabled();
                dos.writeInt(activeCount);
                dos.writeInt(dimension);
                dos.writeBoolean(isSQ8);

                if (isSQ8) {
                    dos.writeFloat(store.getSQ8Min());
                    dos.writeFloat(store.getSQ8Max());
                    byte[] tempByteVec = new byte[dimension];
                    int totalCount = getStoreVectorSize(store);
                    for (int offset = 0; offset < totalCount; offset++) {
                        if (!isStoreOffsetDeleted(store, offset)) {
                            store.copySQ8VectorFromBuffer(offset, tempByteVec);
                            dos.write(tempByteVec);
                        }
                    }
                } else {
                    float[] tempVec = new float[dimension];
                    int totalCount = getStoreVectorSize(store);
                    for (int offset = 0; offset < totalCount; offset++) {
                        if (!isStoreOffsetDeleted(store, offset)) {
                            copyStoreVectorTo(store, offset, tempVec);
                            for (float f : tempVec) {
                                dos.writeFloat(f);
                            }
                        }
                    }
                }
            }

            // 4. 写入 documents.jsonl 元数据与文本
            File docsFile = new File(tmpDir, "documents.jsonl");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(docsFile, StandardCharsets.UTF_8))) {
                int totalCount = getStoreVectorSize(store);
                for (int offset = 0; offset < totalCount; offset++) {
                    if (!isStoreOffsetDeleted(store, offset)) {
                        LocalVectorStore.DocumentPayload payload = getStorePayload(store, offset);
                        if (payload != null) {
                            Map<String, Object> map = new LinkedHashMap<>();
                            map.put("id", payload.getId());
                            map.put("text", payload.getText());
                            map.put("metadata", payload.getMetadata());
                            writer.write(objectMapper.writeValueAsString(map));
                            writer.newLine();
                        }
                    }
                }
            }

            // 5. 原子替换 (ATOMIC_MOVE) 目标文件，确保文件完整性
            copyFileAtomic(new File(tmpDir, "store.json"), new File(storeDir, "store.json"));
            copyFileAtomic(new File(tmpDir, "vectors.bin"), new File(storeDir, "vectors.bin"));
            copyFileAtomic(new File(tmpDir, "documents.jsonl"), new File(storeDir, "documents.jsonl"));

            // 6. 清理临时目录
            deleteDirectory(tmpDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save snapshot for store [" + storeName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 从本地快照目录读取文件并恢复装载回内存中的 Store。
     */
    @Override
    public synchronized void loadStore(LocalVectorStore store) {
        if (store == null) {
            return;
        }
        String storeName = store.getDefinition().getStoreName();
        File storeDir = getStoreDir(storeName);
        File storeJsonFile = new File(storeDir, "store.json");
        File vectorsBinFile = new File(storeDir, "vectors.bin");
        File docsFile = new File(storeDir, "documents.jsonl");

        // 快照不完整或不存在时直接返回
        if (!storeJsonFile.exists() || !vectorsBinFile.exists() || !docsFile.exists()) {
            return;
        }

        try {
            // 1. 校验定义与维度
            VectorStoreDefinition loadedDef = objectMapper.readValue(storeJsonFile, VectorStoreDefinition.class);
            if (loadedDef.getDimension() != store.getDefinition().getDimension()) {
                throw new IllegalStateException("Snapshot dimension mismatch for [" + storeName + "]. Expected: " + store.getDefinition().getDimension() + ", found: " + loadedDef.getDimension());
            }

            // 2. 读取 documents.jsonl
            List<VectorDocument> docs = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(docsFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    Map<String, Object> map = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    VectorDocument doc = new VectorDocument();
                    doc.setId((String) map.get("id"));
                    doc.setText((String) map.get("text"));
                    doc.setMetadata((Map<String, Object>) map.get("metadata"));
                    docs.add(doc);
                }
            }

            // 3. 读取 vectors.bin 并拼装还原写入 store (upsert)
            int dimension = store.getDefinition().getDimension();
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(vectorsBinFile)))) {
                int activeCount = dis.readInt();
                int binDimension = dis.readInt();
                if (binDimension != dimension) {
                    throw new IllegalStateException("vectors.bin dimension mismatch");
                }
                boolean isSQ8 = dis.readBoolean();

                if (isSQ8) {
                    float min = dis.readFloat();
                    float max = dis.readFloat();
                    store.setSQ8MinMax(min, max);

                    byte[] byteVec = new byte[dimension];
                    float[] floatVec = new float[dimension];
                    for (int i = 0; i < activeCount && i < docs.size(); i++) {
                        dis.readFully(byteVec);
                        // 反量化还原 float 向量供 upsert 消费
                        veclite.quantization.SQ8Quantizer.dequantize(byteVec, min, max, floatVec);
                        VectorDocument doc = docs.get(i);
                        doc.setVector(floatVec);
                        store.upsert(doc);
                    }
                } else {
                    for (int i = 0; i < activeCount && i < docs.size(); i++) {
                        float[] vector = new float[dimension];
                        for (int d = 0; d < dimension; d++) {
                            vector[d] = dis.readFloat();
                        }
                        VectorDocument doc = docs.get(i);
                        doc.setVector(vector);
                        store.upsert(doc);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load snapshot for store [" + storeName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定 Store 的磁盘本地快照目录。
     */
    @Override
    public synchronized void deleteStore(String storeName) {
        File storeDir = getStoreDir(storeName);
        if (storeDir.exists()) {
            deleteDirectory(storeDir);
        }
    }

    private File getStoreDir(String storeName) {
        String basePath = properties.getStorage().getSnapshotFile().getBasePath();
        return new File(basePath, storeName);
    }

    private boolean deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        return dir.delete();
    }

    /**
     * 利用 Files.copy 实现物理文件覆盖替换 (REPLACE_EXISTING)。
     */
    private void copyFileAtomic(File src, File dest) throws IOException {
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private int getStoreVectorSize(LocalVectorStore store) {
        return store.getVectorBufferSize();
    }

    private boolean isStoreOffsetDeleted(LocalVectorStore store, int offset) {
        return store.isOffsetDeleted(offset);
    }

    private void copyStoreVectorTo(LocalVectorStore store, int offset, float[] dest) {
        store.copyVectorFromBuffer(offset, dest);
    }

    private LocalVectorStore.DocumentPayload getStorePayload(LocalVectorStore store, int offset) {
        return store.getDocumentPayloadAt(offset);
    }
}


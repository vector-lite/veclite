package veclite.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.ConsistencyException;
import veclite.engine.LocalVectorStore;
import veclite.engine.LocalVectorStoreAssertions;
import veclite.model.VectorDocument;

import java.io.*;
import java.nio.charset.StandardCharsets;
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

    private static final Logger log = LoggerFactory.getLogger(SnapshotFileStorage.class);

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
        // 落盘前做内部一致性断言（v2.4 § 4.4）：
        // 严格模式：直接抛 ConsistencyException，saveStore 失败（内存数据保留）
        // 非严格模式：打 ERROR 继续落盘
        try {
            LocalVectorStoreAssertions.assertConsistency(store);
        } catch (ConsistencyException ce) {
            if (properties.getConsistency().isStrict()) {
                throw ce;
            }
            log.error("Consistency check failed for store [{}] (non-strict, continuing): {}",
                    storeName, ce.getMessage());
        }
        File storeDir = getStoreDir(storeName);
        if (!storeDir.exists()) {
            storeDir.mkdirs();
        }

        // 1. 创建临时目录 storeName.tmp
        File tmpDir = SnapshotFileUtil.prepareTempDir(storeDir.getParentFile(), storeName);

        try {
            // 2. 写入 store.json 配置
            File storeJsonFile = new File(tmpDir, "store.json");
            objectMapper.writeValue(storeJsonFile, store.getDefinition());

            // 3. 写入 vectors.bin 二进制向量数据
            File vectorsBinFile = new File(tmpDir, "vectors.bin");
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(vectorsBinFile)))) {
                int activeCount = store.getActiveCount();
                int dimension = store.getDefinition().getDimension();
                // 仅当 SQ8 参数已冻结时才以 SQ8 格式落盘；校准预热期(未冻结)退化为 Float32 格式，
                // 加载后重新走校准流程，保证量化参数始终来自完整统计
                boolean isSQ8 = store.isSQ8Enabled() && store.isSQ8Frozen();
                dos.writeInt(activeCount);
                dos.writeInt(dimension);
                dos.writeBoolean(isSQ8);

                if (isSQ8) {
                    // 逐维度量化参数原样落盘，恢复时不经过任何重新计算
                    writeFloatArray(dos, store.getSQ8MinPerDim());
                    writeFloatArray(dos, store.getSQ8ScalePerDim());
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

            // 5. 目录级原子交换：整目录替换，杜绝"三个文件写到一半崩溃导致新旧数据混搭"
            swapDirectoryAtomic(tmpDir, storeDir, storeName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save snapshot for store [" + storeName + "]: " + e.getMessage(), e);
        } finally {
            // 清理可能残留的临时目录
            SnapshotFileUtil.deleteRecursively(tmpDir);
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
            // 1. 校验定义与维度（校验失败直接抛出，不触碰内存中的现有数据）
            VectorStoreDefinition loadedDef = objectMapper.readValue(storeJsonFile, VectorStoreDefinition.class);
            if (loadedDef.getDimension() != store.getDefinition().getDimension()) {
                throw new IllegalStateException("Snapshot dimension mismatch for [" + storeName + "]. Expected: " + store.getDefinition().getDimension() + ", found: " + loadedDef.getDimension());
            }

            // 2. 重置内存状态后再加载，防止快照之外的旧数据残留
            store.reset();

            // 3. 读取 documents.jsonl
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

            // 4. 读取 vectors.bin 并拼装还原写入 store
            int dimension = store.getDefinition().getDimension();
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(vectorsBinFile)))) {
                int activeCount = dis.readInt();
                int binDimension = dis.readInt();
                if (binDimension != dimension) {
                    throw new IllegalStateException("vectors.bin dimension mismatch");
                }
                boolean isSQ8 = dis.readBoolean();

                if (isSQ8) {
                    // 逐维度量化参数直接注入，随后以原始量化字节恢复，不做任何反量化/重量化往返
                    float[] minPerDim = readFloatArray(dis, dimension);
                    float[] scalePerDim = readFloatArray(dis, dimension);
                    store.restoreFrozenParams(minPerDim, scalePerDim);

                    byte[] byteVec = new byte[dimension];
                    for (int i = 0; i < activeCount && i < docs.size(); i++) {
                        dis.readFully(byteVec);
                        store.restoreDocumentWithSQ8(docs.get(i), byteVec);
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
        SnapshotFileUtil.deleteRecursively(storeDir);
    }

    private File getStoreDir(String storeName) {
        String basePath = properties.getStorage().getSnapshotFile().getBasePath();
        return new File(basePath, storeName);
    }

    /**
     * 目录级原子交换委托给 {@link SnapshotFileUtil#swapDirectoryAtomic}。
     */
    private void swapDirectoryAtomic(File tmpDir, File storeDir, String storeName) throws IOException {
        SnapshotFileUtil.swapDirectoryAtomic(tmpDir, storeDir, storeName);
    }

    private void writeFloatArray(DataOutputStream dos, float[] values) throws IOException {
        dos.writeInt(values != null ? values.length : 0);
        if (values != null) {
            for (float v : values) {
                dos.writeFloat(v);
            }
        }
    }

    private float[] readFloatArray(DataInputStream dis, int expectedLength) throws IOException {
        int length = dis.readInt();
        if (length != expectedLength) {
            throw new IllegalStateException("Quantization params length mismatch. Expected: " + expectedLength + ", found: " + length);
        }
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = dis.readFloat();
        }
        return values;
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


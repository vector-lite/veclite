package veclite.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 序列化和反序列化 LocalVectorStore 快照的公共工具。
 * <p>同时被 {@link SnapshotFileStorage} 和 {@link OssSnapshotStorage} 使用。
 */
public class SnapshotSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * LocalVectorStore 的 3 段二进制数据。
     */
    public static class Blob {
        public final byte[] storeJson;
        public final byte[] vectorsBin;
        public final byte[] documentsJsonl;

        public Blob(byte[] storeJson, byte[] vectorsBin, byte[] documentsJsonl) {
            this.storeJson = storeJson;
            this.vectorsBin = vectorsBin;
            this.documentsJsonl = documentsJsonl;
        }
    }

    /**
     * 把 LocalVectorStore 序列化为 3 段字节流。
     * 与原 {@code SnapshotFileStorage.saveStore} 字节级一致。
     */
    public static Blob serialize(LocalVectorStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store is null");
        }
        // 1. store.json
        byte[] storeJson;
        try {
            storeJson = MAPPER.writeValueAsBytes(store.getDefinition());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize store.json", e);
        }

        // 2. vectors.bin
        java.io.ByteArrayOutputStream binOut = new java.io.ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(binOut)) {
            int activeCount = store.getActiveCount();
            int dimension = store.getDefinition().getDimension();
            boolean isSQ8 = store.isSQ8Enabled() && store.isSQ8Frozen();

            dos.writeInt(activeCount);
            dos.writeInt(dimension);
            dos.writeBoolean(isSQ8);

            if (isSQ8) {
                writeFloatArray(dos, store.getSQ8MinPerDim());
                writeFloatArray(dos, store.getSQ8ScalePerDim());
                byte[] tempByteVec = new byte[dimension];
                int totalCount = store.getVectorBufferSize();
                for (int offset = 0; offset < totalCount; offset++) {
                    if (!store.isOffsetDeleted(offset)) {
                        store.copySQ8VectorFromBuffer(offset, tempByteVec);
                        dos.write(tempByteVec);
                    }
                }
            } else {
                float[] tempVec = new float[dimension];
                int totalCount = store.getVectorBufferSize();
                for (int offset = 0; offset < totalCount; offset++) {
                    if (!store.isOffsetDeleted(offset)) {
                        store.copyVectorFromBuffer(offset, tempVec);
                        for (float f : tempVec) {
                            dos.writeFloat(f);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize vectors.bin", e);
        }
        byte[] vectorsBin = binOut.toByteArray();

        // 3. documents.jsonl
        java.io.ByteArrayOutputStream docOut = new java.io.ByteArrayOutputStream();
        try (OutputStream os = docOut) {
            int totalCount = store.getVectorBufferSize();
            for (int offset = 0; offset < totalCount; offset++) {
                if (!store.isOffsetDeleted(offset)) {
                    LocalVectorStore.DocumentPayload payload = store.getDocumentPayloadAt(offset);
                    if (payload != null) {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", payload.getId());
                        map.put("text", payload.getText());
                        map.put("metadata", payload.getMetadata());
                        os.write(MAPPER.writeValueAsBytes(map));
                        os.write('\n');
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize documents.jsonl", e);
        }
        byte[] documentsJsonl = docOut.toByteArray();

        return new Blob(storeJson, vectorsBin, documentsJsonl);
    }

    /**
     * 把 3 段字节流反序列化进 LocalVectorStore。
     * <p>调用方需先调 {@code store.reset()} 或在空 store 上调用。
     */
    public static void deserialize(LocalVectorStore store,
                                    byte[] storeJson,
                                    byte[] vectorsBin,
                                    byte[] documentsJsonl) {
        if (store == null) {
            throw new IllegalArgumentException("store is null");
        }
        try {
            // 1. 校验 store.json 维度
            VectorStoreDefinition loadedDef = MAPPER.readValue(storeJson, VectorStoreDefinition.class);
            if (loadedDef.getDimension() != store.getDefinition().getDimension()) {
                throw new IllegalStateException("store.json dimension mismatch. Expected: "
                        + store.getDefinition().getDimension() + ", actual: " + loadedDef.getDimension());
            }

            // 2. 解析 documents.jsonl
            String docsStr = new String(documentsJsonl, StandardCharsets.UTF_8);
            java.util.List<Map<String, Object>> docs = new java.util.ArrayList<>();
            for (String line : docsStr.split("\n")) {
                if (line.trim().isEmpty()) continue;
                Map<String, Object> map = MAPPER.readValue(line,
                        new TypeReference<Map<String, Object>>() {});
                docs.add(map);
            }

            // 3. 解析 vectors.bin
            try (java.io.DataInputStream dis = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(vectorsBin))) {
                int activeCount = dis.readInt();
                int binDimension = dis.readInt();
                if (binDimension != store.getDefinition().getDimension()) {
                    throw new IllegalStateException("vectors.bin dimension mismatch");
                }
                boolean isSQ8 = dis.readBoolean();
                if (isSQ8) {
                    float[] minPerDim = readFloatArray(dis, store.getDefinition().getDimension());
                    float[] scalePerDim = readFloatArray(dis, store.getDefinition().getDimension());
                    store.restoreFrozenParams(minPerDim, scalePerDim);

                    byte[] byteVec = new byte[store.getDefinition().getDimension()];
                    for (int i = 0; i < activeCount && i < docs.size(); i++) {
                        dis.readFully(byteVec);
                        Map<String, Object> docMap = docs.get(i);
                        veclite.model.VectorDocument doc = new veclite.model.VectorDocument();
                        doc.setId((String) docMap.get("id"));
                        doc.setText((String) docMap.get("text"));
                        doc.setMetadata((Map<String, Object>) docMap.get("metadata"));
                        store.restoreDocumentWithSQ8(doc, byteVec);
                    }
                } else {
                    for (int i = 0; i < activeCount && i < docs.size(); i++) {
                        float[] vector = new float[store.getDefinition().getDimension()];
                        for (int d = 0; d < store.getDefinition().getDimension(); d++) {
                            vector[d] = dis.readFloat();
                        }
                        Map<String, Object> docMap = docs.get(i);
                        veclite.model.VectorDocument doc = new veclite.model.VectorDocument();
                        doc.setId((String) docMap.get("id"));
                        doc.setText((String) docMap.get("text"));
                        doc.setMetadata((Map<String, Object>) docMap.get("metadata"));
                        doc.setVector(vector);
                        store.upsert(doc);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize store snapshot: " + e.getMessage(), e);
        }
    }

    private static void writeFloatArray(DataOutputStream dos, float[] values) throws java.io.IOException {
        dos.writeInt(values != null ? values.length : 0);
        if (values != null) {
            for (float v : values) {
                dos.writeFloat(v);
            }
        }
    }

    private static float[] readFloatArray(java.io.DataInputStream dis, int expectedLength)
            throws java.io.IOException {
        int length = dis.readInt();
        if (length != expectedLength) {
            throw new IllegalStateException("float array length mismatch. Expected: "
                    + expectedLength + ", actual: " + length);
        }
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = dis.readFloat();
        }
        return values;
    }
}

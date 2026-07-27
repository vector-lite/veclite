package com.hexin.vector.lite.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hexin.vector.lite.api.VectorStoreDefinition;
import com.hexin.vector.lite.config.VectorLiteProperties;
import com.hexin.vector.lite.engine.LocalVectorStore;
import com.hexin.vector.lite.model.VectorDocument;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SnapshotFileStorage implements VectorPersistenceStorage {

    private final VectorLiteProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SnapshotFileStorage(VectorLiteProperties properties) {
        this.properties = properties;
    }

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

        File tmpDir = new File(storeDir.getParentFile(), storeName + ".tmp");
        if (tmpDir.exists()) {
            deleteDirectory(tmpDir);
        }
        tmpDir.mkdirs();

        try {
            File storeJsonFile = new File(tmpDir, "store.json");
            objectMapper.writeValue(storeJsonFile, store.getDefinition());

            File vectorsBinFile = new File(tmpDir, "vectors.bin");
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(vectorsBinFile)))) {
                int activeCount = store.getActiveCount();
                int dimension = store.getDefinition().getDimension();
                dos.writeInt(activeCount);
                dos.writeInt(dimension);

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

            copyFileAtomic(new File(tmpDir, "store.json"), new File(storeDir, "store.json"));
            copyFileAtomic(new File(tmpDir, "vectors.bin"), new File(storeDir, "vectors.bin"));
            copyFileAtomic(new File(tmpDir, "documents.jsonl"), new File(storeDir, "documents.jsonl"));

            deleteDirectory(tmpDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save snapshot for store [" + storeName + "]: " + e.getMessage(), e);
        }
    }

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

        if (!storeJsonFile.exists() || !vectorsBinFile.exists() || !docsFile.exists()) {
            return;
        }

        try {
            VectorStoreDefinition loadedDef = objectMapper.readValue(storeJsonFile, VectorStoreDefinition.class);
            if (loadedDef.getDimension() != store.getDefinition().getDimension()) {
                throw new IllegalStateException("Snapshot dimension mismatch for [" + storeName + "]. Expected: " + store.getDefinition().getDimension() + ", found: " + loadedDef.getDimension());
            }

            List<VectorDocument> docs = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(docsFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> map = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    VectorDocument doc = new VectorDocument();
                    doc.setId((String) map.get("id"));
                    doc.setText((String) map.get("text"));
                    doc.setMetadata((Map<String, Object>) map.get("metadata"));
                    docs.add(doc);
                }
            }

            int dimension = store.getDefinition().getDimension();
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(vectorsBinFile)))) {
                int activeCount = dis.readInt();
                int binDimension = dis.readInt();
                if (binDimension != dimension) {
                    throw new IllegalStateException("vectors.bin dimension mismatch");
                }

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
        } catch (Exception e) {
            throw new RuntimeException("Failed to load snapshot for store [" + storeName + "]: " + e.getMessage(), e);
        }
    }

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

    private void copyFileAtomic(File src, File dest) throws IOException {
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

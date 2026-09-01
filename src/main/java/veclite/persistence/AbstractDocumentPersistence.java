package veclite.persistence;

import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreMetadata;
import veclite.engine.LocalVectorStore;
import veclite.model.StorageType;
import veclite.model.VectorDocument;
import veclite.config.VectorLiteProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 文档型持久化（单一真相源）的编排基类。
 * <p>
 * 职责分工：真相源持有文档（text/metadata/向量），内存 Store 是它的可重建投影；
 * 量化结构（SQ8 buffer 等）是运行时派生物，不入真相源。编排逻辑（写透、整库装载、整库对账）
 * 与具体存储无关，只依赖 {@link VectorDocumentRepository} 端口，因此在这里实现一次，
 * 由各存储子类提供仓储与 {@link StorageType} 即可。
 * <ul>
 *   <li><b>写透</b>（{@link #upsertDocuments}）：写路径先提交真相源再更新内存，RPO=0；</li>
 *   <li><b>整库装载</b>（{@link #loadStore}）：游标流式扫描真相源重建内存结构，
 *       SQ8 冻结参数直接注入，避免"反量化→重量化"的精度衰减；</li>
 *   <li><b>整库对账</b>（{@link #saveStore}）：以内存为准修复真相源漂移
 *       （补缺失文档、清滞留行），并同步 Store 元数据。</li>
 * </ul>
 */
public abstract class AbstractDocumentPersistence implements DocumentBackedPersistence {

    private final VectorDocumentRepository repository;
    private final StorageType storageType;
    private final VectorLiteProperties properties;

    protected AbstractDocumentPersistence(VectorDocumentRepository repository,
                                          StorageType storageType,
                                          VectorLiteProperties properties) {
        this.repository = repository;
        this.storageType = storageType;
        this.properties = properties;
    }

    protected VectorDocumentRepository repository() {
        return repository;
    }

    protected VectorLiteProperties properties() {
        return properties;
    }

    /**
     * 整库对账：将内存中的有效文档全量 upsert 进真相源，删除真相源中内存已不存在的滞留行，
     * 最后同步 Store 元数据。对已冻结的 SQ8 Store 落库量化字节（配合逐维参数），
     * 保证"落库 → 装载"位级精确往返，不引入任何反量化误差。
     */
    @Override
    public synchronized void saveStore(LocalVectorStore store) {
        if (store == null) {
            return;
        }
        String storeName = store.getDefinition().getStoreName();
        repository.ensureStore(storeName);
        List<VectorDocumentEntity> entities = buildEntities(store);
        repository.upsertBatch(storeName, entities);

        Set<String> memoryIds = new HashSet<>(entities.size());
        for (VectorDocumentEntity entity : entities) {
            memoryIds.add(entity.getDocId());
        }
        List<String> staleIds = new ArrayList<>();
        for (String remoteId : repository.listDocumentIds(storeName)) {
            if (!memoryIds.contains(remoteId)) {
                staleIds.add(remoteId);
            }
        }
        repository.deleteByIds(storeName, staleIds);

        repository.saveStoreMetadata(buildMetadata(store));
    }

    /**
     * 整库装载：重置内存 Store 后，从真相源游标式重建全部文档。
     * SQ8 冻结参数（若存在）先于任何文档写入注入 {@code restoreFrozenParams}，
     * 使 SQ8 文档走 {@code restoreDocumentWithSQ8} 直通路径、FLOAT32 文档走常规量化写入路径。
     */
    @Override
    public synchronized void loadStore(LocalVectorStore store) {
        if (store == null) {
            return;
        }
        String storeName = store.getDefinition().getStoreName();
        repository.ensureStore(storeName);
        int dimension = store.getDefinition().getDimension();

        VectorStoreMetadata metadata = repository.findStoreMetadata(storeName).orElse(null);
        if (metadata != null && metadata.getDimension() != dimension) {
            throw new IllegalStateException("Document persistence dimension mismatch for store [" + storeName
                    + "]. Expected: " + dimension + ", found: " + metadata.getDimension());
        }
        // reset 会连同 SQ8 冻结参数一起清空，因此参数注入必须放在 reset 之后
        store.reset();
        if (metadata != null && metadata.getSq8MinPerDim() != null && metadata.getSq8ScalePerDim() != null) {
            if (!store.isSQ8Enabled()) {
                throw new IllegalStateException("Store [" + storeName + "] has frozen SQ8 params in persistence "
                        + "but is not SQ8 enabled");
            }
            store.restoreFrozenParams(metadata.getSq8MinPerDim(), metadata.getSq8ScalePerDim());
        }

        int restored = 0;
        Iterator<VectorDocumentEntity> cursor = repository.scan(storeName);
        while (cursor.hasNext()) {
            VectorDocumentEntity entity = cursor.next();
            if (entity.getSq8Vector() != null) {
                if (!store.isSQ8Frozen()) {
                    throw new IllegalStateException("Store [" + storeName + "] persisted SQ8 document ["
                            + entity.getDocId() + "] but frozen params are missing");
                }
                store.restoreDocumentWithSQ8(toDocument(entity), entity.getSq8Vector());
            } else {
                if (entity.getVector() == null || entity.getVector().length != dimension) {
                    throw new IllegalStateException("Persisted vector dimension mismatch for store [" + storeName
                            + "] document [" + entity.getDocId() + "]. Expected: " + dimension
                            + ", actual: " + (entity.getVector() != null ? entity.getVector().length : 0));
                }
                store.upsert(toDocument(entity));
            }
            restored++;
        }

        if (metadata != null) {
            metadata.setActiveCount(store.getActiveCount());
            repository.saveStoreMetadata(metadata);
        }
    }

    @Override
    public synchronized void deleteStore(String storeName) {
        repository.dropStore(storeName);
        repository.deleteStoreMetadata(storeName);
    }

    /**
     * 文档写透：真相源先提交（FLOAT32 原始向量），由调用方在真相源提交成功后再更新内存。
     */
    @Override
    public void upsertDocuments(LocalVectorStore store, List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        String storeName = store.getDefinition().getStoreName();
        List<VectorDocumentEntity> entities = new ArrayList<>(documents.size());
        for (VectorDocument document : documents) {
            if (document == null || document.getId() == null) {
                throw new IllegalArgumentException("Document and Document ID must not be null");
            }
            if (document.getVector() == null) {
                throw new IllegalArgumentException("Document [" + document.getId()
                        + "] must carry a vector before write-through persistence");
            }
            entities.add(VectorDocumentEntity.float32(document.getId(), document.getText(),
                    document.getMetadata(), document.getVector()));
        }
        repository.upsertBatch(storeName, entities);
    }

    @Override
    public void deleteDocuments(String storeName, List<String> documentIds) {
        repository.deleteByIds(storeName, documentIds);
    }

    @Override
    public void saveStoreMetadata(LocalVectorStore store) {
        repository.ensureStore(store.getDefinition().getStoreName());
        repository.saveStoreMetadata(buildMetadata(store));
    }

    @Override
    public List<VectorStoreMetadata> listStoreMetadata() {
        return repository.listStoreMetadata();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 文档型后端的 Store 注册表即真相源元数据集合，直接由仓储列出。
     */
    @Override
    public List<String> listStoreNames() {
        List<String> names = new ArrayList<>();
        for (VectorStoreMetadata metadata : repository.listStoreMetadata()) {
            names.add(metadata.getStoreName());
        }
        return names;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从真相源元数据恢复 Store 定义；不存在时返回 null，由调用方决定是否跳过。
     */
    @Override
    public VectorStoreDefinition loadStoreDefinition(String storeName) {
        return repository.findStoreMetadata(storeName)
                .map(VectorStoreMetadata::toDefinition)
                .orElse(null);
    }

    /** 释放底层连接（Spring 容器销毁时自动推断调用）。{@link VectorPersistenceStorage} 端口未声明 close，由实现暴露 */
    public void close() {
        repository.close();
    }

    /**
     * 从内存 Store 构建落库实体：SQ8 冻结态落量化字节（配合逐维参数），
     * 其余（含校准期未冻结）落原始 Float32。
     */
    private List<VectorDocumentEntity> buildEntities(LocalVectorStore store) {
        int dimension = store.getDefinition().getDimension();
        int totalCount = store.getVectorBufferSize();
        List<VectorDocumentEntity> entities = new ArrayList<>(store.getActiveCount());
        float[] tempVector = new float[dimension];
        for (int offset = 0; offset < totalCount; offset++) {
            if (store.isOffsetDeleted(offset)) {
                continue;
            }
            LocalVectorStore.DocumentPayload payload = store.getDocumentPayloadAt(offset);
            if (payload == null) {
                continue;
            }
            store.copyVectorFromBuffer(offset, tempVector);
            entities.add(VectorDocumentEntity.float32(payload.getId(), payload.getText(), payload.getMetadata(),
                    tempVector.clone()));
        }
        return entities;
    }

    private VectorStoreMetadata buildMetadata(LocalVectorStore store) {
        VectorStoreMetadata metadata = VectorStoreMetadata.fromDefinition(store.getDefinition(), storageType);
        metadata.setActiveCount(store.getActiveCount());
        if (store.isSQ8Enabled() && store.isSQ8Frozen()) {
            metadata.setSq8MinPerDim(store.getSQ8MinPerDim());
            metadata.setSq8ScalePerDim(store.getSQ8ScalePerDim());
        }
        return metadata;
    }

    private VectorDocument toDocument(VectorDocumentEntity entity) {
        VectorDocument document = new VectorDocument();
        document.setId(entity.getDocId());
        document.setText(entity.getText());
        document.setMetadata(entity.getMetadata());
        if (entity.getVector() != null) {
            document.setVector(entity.getVector());
        } else {
            // SQ8 文档的向量由 restoreDocumentWithSQ8 直接注入量化字节，绕过反量化往返
            document.setVector(new float[0]);
        }
        return document;
    }
}

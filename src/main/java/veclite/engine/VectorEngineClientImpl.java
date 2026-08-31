package veclite.engine;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingService;
import veclite.model.*;
import veclite.persistence.VectorPersistenceStorage;
import veclite.persistence.meta.VectorMetadataRepository;
import veclite.persistence.meta.VectorStoreMetadata;

import java.util.List;

/**
 * 向量引擎客户端实现类。
 * <p>
 * 统一对外提供向量库的生命周期管理、文档写入（Upsert）、向量与文本检索、删除以及持久化刷盘与恢复能力。
 */
public class VectorEngineClientImpl implements VectorEngineClient {

    /** 本地向量引擎（管理 Store 实例映射） */
    private final LocalVectorEngine localVectorEngine;

    /** 文本 Embedding 服务 */
    private final EmbeddingService embeddingService;

    /** 持久化存储接口 */
    private final VectorPersistenceStorage persistence;

    /** 全局配置属性 */
    private final VectorLiteProperties properties;

    /** PG 元数据仓储（可选，集群模式下存在） */
    private final VectorMetadataRepository metadataRepository;

    public VectorEngineClientImpl(LocalVectorEngine localVectorEngine,
                                  EmbeddingProvider embeddingProvider,
                                  VectorPersistenceStorage persistence,
                                  VectorLiteProperties properties) {
        this(localVectorEngine, embeddingProvider, persistence, properties, null);
    }

    public VectorEngineClientImpl(LocalVectorEngine localVectorEngine,
                                  EmbeddingProvider embeddingProvider,
                                  VectorPersistenceStorage persistence,
                                  VectorLiteProperties properties,
                                  VectorMetadataRepository metadataRepository) {
        this.localVectorEngine = localVectorEngine;
        this.embeddingService = embeddingProvider != null ? new EmbeddingService(embeddingProvider, properties) : null;
        this.persistence = persistence;
        this.properties = properties;
        this.metadataRepository = metadataRepository;

        // 集群模式：先从 PG listAll 发现；单 pod 回退 yml
        if (metadataRepository != null) {
            initStoresFromPg();
        } else {
            initStoresFromProperties();
        }
    }

    /**
     * 集群模式：启动时从 PG listAll 发现所有 store 并加载。
     */
    private void initStoresFromPg() {
        java.util.List<VectorStoreMetadata> all = metadataRepository.listAll();
        for (VectorStoreMetadata m : all) {
            try {
                VectorStoreDefinition definition = new VectorStoreDefinition();
                definition.setStoreName(m.getStoreName());
                definition.setDimension(m.getDimension());
                definition.setMetric(m.getMetric());
                definition.setMaxCapacity(m.getMaxCapacity());
                definition.setEmbeddingModel(m.getEmbeddingModel());
                definition.setEmbeddingModelVersion(m.getEmbeddingModelVersion());
                definition.setQuantization(m.getQuantization());
                definition.setIndexedMetadataFields(m.getIndexedMetadataFields());
                localVectorEngine.createStore(m.getStoreName(), definition);
                LocalVectorStore store = localVectorEngine.getStore(m.getStoreName());
                persistence.loadStore(store);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(VectorEngineClientImpl.class)
                        .error("Failed to init store [{}] from PG: {}", m.getStoreName(), e.getMessage());
            }
        }
    }

    /**
     * 单 pod 模式：根据 application.yml 中的配置初始化 VectorStore，并自动加载磁盘快照。
     */
    private void initStoresFromProperties() {
        if (properties.getStores() != null) {
            properties.getStores().forEach((storeName, config) -> {
                VectorStoreDefinition definition = new VectorStoreDefinition();
                definition.setStoreName(storeName);
                definition.setDimension(config.getDimension());
                definition.setMetric(config.getMetric());
                definition.setMaxCapacity(config.getMaxCapacity());
                definition.setEmbeddingModel(config.getEmbeddingModel());
                definition.setQuantization(config.getQuantization());
                definition.setIndexedMetadataFields(config.getIndexedMetadataFields());

                // 1. 创建内存中的 Store
                localVectorEngine.createStore(storeName, definition);

                // 2. 自动从持久化目录恢复数据快照（若存在）
                LocalVectorStore store = localVectorEngine.getStore(storeName);
                persistence.loadStore(store);
            });
        }
    }

    /**
     * 手动创建指定的向量库 Store。
     */
    @Override
    public void createStore(String storeName, VectorStoreDefinition definition) {
        localVectorEngine.createStore(storeName, definition);
    }

    /**
     * 单条写入/更新向量文档。
     */
    @Override
    public void upsert(String storeName, VectorDocument document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("Document and Document ID must not be null");
        }
        if (document.getVector() == null && document.getText() == null) {
            throw new IllegalArgumentException("Document must contain either a vector or text");
        }
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        if (document.getVector() == null) {
            autoEmbed(store, List.of(document));
        }
        store.upsert(document);
    }

    /**
     * 批量写入/更新向量文档。
     */
    @Override
    public void upsertBatch(String storeName, List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        List<VectorDocument> needEmbed = new java.util.ArrayList<>();
        for (VectorDocument doc : documents) {
            if (doc == null || doc.getId() == null) {
                throw new IllegalArgumentException("Document and Document ID must not be null");
            }
            if (doc.getVector() == null && doc.getText() == null) {
                throw new IllegalArgumentException("Document must contain either a vector or text");
            }
            if (doc.getVector() == null) {
                needEmbed.add(doc);
            }
        }
        if (!needEmbed.isEmpty()) {
            autoEmbed(store, needEmbed);
        }
        for (VectorDocument doc : documents) {
            store.upsert(doc);
        }
    }

    private void autoEmbed(LocalVectorStore store, List<VectorDocument> docs) {
        String modelName = store.getDefinition().getEmbeddingModel();
        String modelVersion = store.getDefinition().getEmbeddingModelVersion();
        if (modelName == null) {
            throw new IllegalStateException("Store [" + store.getDefinition().getStoreName() + "] has no embedding model bound to embed text.");
        }
        if (embeddingService == null) {
            throw new IllegalStateException("No EmbeddingService configured for store: " + store.getDefinition().getStoreName());
        }
        List<String> texts = new java.util.ArrayList<>(docs.size());
        for (VectorDocument doc : docs) {
            texts.add(doc.getText());
        }
        int targetDim = store.getDefinition().getDimension();
        List<List<Float>> embedded = embeddingService.embedTexts(modelName, modelVersion, texts, targetDim);
        for (int i = 0; i < docs.size(); i++) {
            List<Float> list = embedded.get(i);
            float[] vec = new float[list.size()];
            for (int j = 0; j < list.size(); j++) {
                vec[j] = list.get(j);
            }
            docs.get(i).setVector(vec);
        }
    }

    /**
     * 根据原始向量做余弦/欧氏距离 TopK 检索。
     */
    @Override
    public List<VectorSearchResult> searchByVector(VectorSearchRequest request) {
        if (request == null || request.getStoreName() == null) {
            throw new IllegalArgumentException("Search request and storeName must not be null");
        }
        LocalVectorStore store = localVectorEngine.getStore(request.getStoreName());
        return store.search(request);
    }

    /**
     * 将输入文本转化为向量后，进行 TopK 检索。
     */
    @Override
    public List<VectorSearchResult> searchByText(VectorSearchRequest request) {
        if (request == null || request.getStoreName() == null || request.getQueryText() == null) {
            throw new IllegalArgumentException("Search request, storeName, and queryText must not be null");
        }
        LocalVectorStore store = localVectorEngine.getStore(request.getStoreName());
        String modelName = store.getDefinition().getEmbeddingModel();
        String modelVersion = store.getDefinition().getEmbeddingModelVersion();
        if (modelName == null) {
            modelName = properties.getEmbedding().getDefaultModel();
        }
        if (embeddingService == null || modelName == null) {
            throw new IllegalStateException("No EmbeddingProvider or embedding model configured for store: " + request.getStoreName());
        }

        // 调用 Embedding 模型计算查询文本的向量，传入 store 期望的维度
        int targetDim = localVectorEngine.getStore(request.getStoreName()).getDefinition().getDimension();
        List<Float> floatList = embeddingService.embed(modelName, modelVersion, request.getQueryText(), targetDim);
        float[] vector = new float[floatList.size()];
        for (int i = 0; i < floatList.size(); i++) {
            vector[i] = floatList.get(i);
        }
        request.setQueryVector(vector);
        return store.search(request);
    }

    /**
     * 混合检索入口（目前文本优先转向量后检索）。
     */
    @Override
    public List<VectorSearchResult> hybridSearch(VectorSearchRequest request) {
        if (request.getQueryVector() == null && request.getQueryText() != null) {
            return searchByText(request);
        }
        return searchByVector(request);
    }

    /**
     * 根据 ID 列表批量删除文档。
     */
    @Override
    public DeleteResult deleteByIds(String storeName, List<String> ids) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        return store.deleteByIds(ids);
    }

    /**
     * 根据元数据 Filter 条件条件删除文档。
     */
    @Override
    public DeleteResult deleteByFilter(String storeName, FilterExpression filter) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        return store.deleteByFilter(filter);
    }

    @Override
    public VectorDocumentPage listDocuments(String storeName, int page, int size) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);

        // 文档列表用于管理/调试页面，不应默认把高维向量传回客户端。
        // LocalVectorStore 的分页约定从第 1 页开始，并会在内部跳过已删除文档；
        // 这里先归一化参数，再补齐 API 层要求的分页元数据。
        int safePage = Math.max(1, page);
        int safeSize = size > 0 ? size : 20;
        List<VectorDocument> items = store.listDocuments(safePage, safeSize, false);
        return new VectorDocumentPage(items, safePage, safeSize, store.getActiveCount());
    }

    /**
     * 获取指定 Store 的容量与配置状态统计信息。
     */
    @Override
    public VectorStoreStats stats(String storeName) {
        VectorStoreStats stats = localVectorEngine.stats(storeName);
        stats.setStorageSource(detectStorageSource());
        // 把 store 的 embeddingModel 一起带上，前端创建时填了什么一目了然
        try {
            stats.setEmbeddingModel(localVectorEngine.getStore(storeName)
                    .getDefinition().getEmbeddingModel());
        } catch (Exception ignored) {
            stats.setEmbeddingModel(null);
        }
        return stats;
    }

    private String detectStorageSource() {
        String cls = persistence.getClass().getSimpleName();
        if (cls.contains("Oss")) return "OSS";
        if (cls.contains("SnapshotFile")) return "LOCAL";
        if (cls.contains("Noop")) return "IN_MEMORY";
        return "UNKNOWN";
    }

    /**
     * 手动将指定 Store 刷盘持久化到本地。
     */
    @Override
    public void refresh(String storeName) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        persistence.saveStore(store);
    }

    /**
     * 手动从本地持久化磁盘文件重载指定 Store 的数据。
     */
    @Override
    public void reload(String storeName) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        persistence.loadStore(store);
    }
}

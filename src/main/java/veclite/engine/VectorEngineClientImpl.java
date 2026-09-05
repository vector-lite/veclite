package veclite.engine;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreMetadata;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingService;
import veclite.model.*;
import veclite.embedding.EmbeddingModelRegistry;
import veclite.persistence.DocumentBackedPersistence;
import veclite.persistence.VectorPersistenceStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 向量引擎客户端实现类。
 * <p>
 * 统一对外提供向量库的生命周期管理、文档写入（Upsert）、向量与文本检索、删除，
 * 以及持久化的写透、对账（refresh）、全量重建（reload）与增量同步（syncStore）能力。
 */
public class VectorEngineClientImpl implements VectorEngineClient {

    private static final Logger log = LoggerFactory.getLogger(VectorEngineClientImpl.class);

    /** 本地向量引擎（管理 Store 实例映射） */
    private final LocalVectorEngine localVectorEngine;
    
    /** 文本 Embedding 服务 */
    private final EmbeddingService embeddingService;
    
    /** 持久化存储接口 */
    private final VectorPersistenceStorage persistence;

    /** 文档型持久化编排端口（MongoDB 等单一真相源方案）；快照/Noop 实现下为 null，写透与发现逻辑自动旁路 */
    private final DocumentBackedPersistence documentPersistence;

    /** 全局配置属性 */
    private final VectorLiteProperties properties;

    public VectorEngineClientImpl(LocalVectorEngine localVectorEngine,
                                  EmbeddingProvider embeddingProvider,
                                  VectorPersistenceStorage persistence,
                                  VectorLiteProperties properties,
                                  EmbeddingModelRegistry embeddingModelRegistry) {
        this.localVectorEngine = localVectorEngine;
        this.embeddingService = embeddingProvider != null
                ? new EmbeddingService(embeddingProvider, embeddingModelRegistry) : null;
        this.persistence = persistence;
        this.documentPersistence = persistence instanceof DocumentBackedPersistence documentBacked
                ? documentBacked
                : null;
        this.properties = properties;

        // 应用启动时，自动初始化配置中的 Store 并加载磁盘快照
        initStoresFromProperties();
    }

    /**
     * 根据 application.yml 中的配置初始化 VectorStore，并自动加载本地持久化快照；
     * 文档型持久化下额外执行元数据发现，恢复未在配置中声明的存量 Store。
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
        discoverPersistedStores();
    }

    /**
     * 启动双发现：文档型持久化下从真相源元数据中发现存量 Store，
     * 补齐 properties 配置中未声明的库并从当前配置的后端装载数据。
     */
    private void discoverPersistedStores() {
        if (documentPersistence == null) {
            return;
        }
        for (VectorStoreMetadata metadata : documentPersistence.listStoreMetadata()) {
            if (localVectorEngine.hasStore(metadata.getStoreName())) {
                continue;
            }
            try {
                localVectorEngine.createStore(metadata.getStoreName(), metadata.toDefinition());
                LocalVectorStore store = localVectorEngine.getStore(metadata.getStoreName());
                persistence.loadStore(store);
            } catch (Exception e) {
                // 单库装载失败（如绑定的 Embedding 数据源缺失）只跳过该库并告警，不阻断启动；
                // 数据源补配后可通过 rediscoverPersistedStores 恢复
                log.warn("Skip store [{}] from persistence discovery: {}",
                        metadata.getStoreName(), e.getMessage());
            }
        }
    }

    @Override
    public void rediscoverPersistedStores() {
        discoverPersistedStores();
    }

    /**
     * 手动创建指定的向量库 Store。
     * 文档型持久化下幂等装载真相源中的既有数据，并登记 Store 元数据。
     */
    @Override
    public void createStore(String storeName, VectorStoreDefinition definition) {
        boolean existed = localVectorEngine.hasStore(storeName);
        try {
            localVectorEngine.createStore(storeName, definition);
            if (documentPersistence != null) {
                LocalVectorStore store = localVectorEngine.getStore(storeName);
                persistence.loadStore(store);
                documentPersistence.saveStoreMetadata(store);
            }
        } catch (RuntimeException failure) {
            // 新 Store 的持久化失败不得留下孤儿元数据/物理表；已有 Store 保留原状态。
            if (!existed) {
                try {
                    if (documentPersistence != null) {
                        try {
                            documentPersistence.deleteStore(storeName);
                        } catch (RuntimeException cleanupFailure) {
                            log.warn("Failed to clean up Store [{}] after create failure: {}", storeName,
                                    cleanupFailure.getMessage());
                        }
                    }
                } finally {
                    localVectorEngine.dropStore(storeName);
                }
            }
            throw failure;
        }
    }

    /**
     * 单条写入/更新向量文档。
     * 文档型持久化下先提交真相源（RPO=0），成功后再更新内存。
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
        if (documentPersistence != null) {
            documentPersistence.upsertDocuments(store, List.of(document));
        }
        store.upsert(document);
    }

    /**
     * 批量写入/更新向量文档。
     * 文档型持久化下以批量写透提交真相源（RPO=0），成功后再更新内存。
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
        if (documentPersistence != null) {
            documentPersistence.upsertDocuments(store, documents);
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
        List<List<Float>> embedded = embeddingService.embedTexts(modelName, modelVersion, texts);
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
            // Store 未绑定模型时回退到数据库维护的默认模型
            modelName = embeddingService != null ? embeddingService.defaultModelName() : null;
        }
        if (embeddingService == null || modelName == null) {
            throw new IllegalStateException("No EmbeddingProvider or embedding model configured for store: " + request.getStoreName());
        }

        // 调用 Embedding 模型计算查询文本的向量
        List<Float> floatList = embeddingService.embed(modelName, modelVersion, request.getQueryText());
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
     * 文档型持久化下先删除真相源，成功后再更新内存位图。
     */
    @Override
    public DeleteResult deleteByIds(String storeName, List<String> ids) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        if (documentPersistence != null && ids != null && !ids.isEmpty()) {
            documentPersistence.deleteDocuments(storeName, ids);
        }
        return store.deleteByIds(ids);
    }

    /**
     * 根据元数据 Filter 条件条件删除文档。
     * 文档型持久化下先取命中 ID 删除真相源，成功后再更新内存位图。
     */
    @Override
    public DeleteResult deleteByFilter(String storeName, FilterExpression filter) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        if (documentPersistence != null && filter != null) {
            List<String> matchedIds = store.findIdsByFilter(filter);
            if (!matchedIds.isEmpty()) {
                documentPersistence.deleteDocuments(storeName, matchedIds);
            }
        }
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
     * 按 ID 精确查询单个文档，包含原始向量，供详情展示使用。
     */
    @Override
    public VectorDocument getDocument(String storeName, String id) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        return store.getDocument(id, true);
    }

    /**
     * 获取指定 Store 的容量与配置状态统计信息。
     */
    @Override
    public VectorStoreStats stats(String storeName) {
        return localVectorEngine.stats(storeName);
    }

    /**
     * 以内存为权威对真相源做集合级对账（补缺失文档、软删滞留行、同步元数据），
     * 返回对账 diff 明细。写透路径下内存与真相源本就一致，这是运维修复工具而非周期性任务。
     */
    @Override
    public ReconcileResult reconcileStore(String storeName) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        if (documentPersistence == null) {
            throw new UnsupportedOperationException("reconcileStore requires a document-backed persistence backend");
        }
        return documentPersistence.reconcileStore(store);
    }

    /**
     * 全量重建：重置内存后从真相源整库装载，并建立增量同步水位基线。
     */
    @Override
    public void reload(String storeName) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        persistence.loadStore(store);
    }

    /**
     * 增量同步：按元数据水位从真相源拉取变更应用到内存。
     * 仅文档型持久化支持；其余后端显式报错，避免定时任务空转造成"已同步"的错觉。
     */
    @Override
    public StoreSyncResult syncStore(String storeName) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        if (documentPersistence == null) {
            throw new IllegalStateException(
                    "Incremental sync requires a document-backed persistence backend (MONGODB/POSTGRES)");
        }
        return documentPersistence.incrementalSync(store);
    }
}

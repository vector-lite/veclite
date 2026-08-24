package veclite.engine;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.model.*;
import veclite.persistence.VectorPersistenceStorage;

import java.util.List;

/**
 * 向量引擎客户端实现类。
 * <p>
 * 统一对外提供向量库的生命周期管理、文档写入（Upsert）、向量与文本检索、删除以及持久化刷盘与恢复能力。
 */
public class VectorEngineClientImpl implements VectorEngineClient {

    /** 本地向量引擎（管理 Store 实例映射） */
    private final LocalVectorEngine localVectorEngine;
    
    /** 文本 Embedding 向量化提供者 */
    private final EmbeddingProvider embeddingProvider;
    
    /** 持久化存储接口 */
    private final VectorPersistenceStorage persistence;
    
    /** 全局配置属性 */
    private final VectorLiteProperties properties;

    public VectorEngineClientImpl(LocalVectorEngine localVectorEngine,
                                  EmbeddingProvider embeddingProvider,
                                  VectorPersistenceStorage persistence,
                                  VectorLiteProperties properties) {
        this.localVectorEngine = localVectorEngine;
        this.embeddingProvider = embeddingProvider;
        this.persistence = persistence;
        this.properties = properties;

        // 应用启动时，自动初始化配置中的 Store 并加载磁盘快照
        initStoresFromProperties();
    }

    /**
     * 根据 application.yml 中的配置初始化 VectorStore，并自动加载本地持久化快照。
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
        LocalVectorStore store = localVectorEngine.getStore(storeName);
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
        for (VectorDocument doc : documents) {
            store.upsert(doc);
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
        if (modelName == null) {
            modelName = properties.getEmbedding().getDefaultModel();
        }
        if (embeddingProvider == null || modelName == null) {
            throw new IllegalStateException("No EmbeddingProvider or embedding model configured for store: " + request.getStoreName());
        }

        // 调用 Embedding 模型计算查询文本的向量
        List<Float> floatList = embeddingProvider.embed(modelName, null, request.getQueryText());
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
        return localVectorEngine.getStore(storeName).listDocuments(page, size);
    }

    /**
     * 获取指定 Store 的容量与配置状态统计信息。
     */
    @Override
    public VectorStoreStats stats(String storeName) {
        return localVectorEngine.stats(storeName);
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

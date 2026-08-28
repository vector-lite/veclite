package veclite.engine;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingService;
import veclite.model.*;
import veclite.persistence.VectorPersistenceStorage;

import java.util.ArrayList;
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

    /** Embedding 服务（用于版本归一化、批处理） */
    private final EmbeddingService embeddingService;

    /** 持久化存储接口 */
    private final VectorPersistenceStorage persistence;

    /** 全局配置属性 */
    private final VectorLiteProperties properties;

    public VectorEngineClientImpl(LocalVectorEngine localVectorEngine,
                                  EmbeddingProvider embeddingProvider,
                                  VectorPersistenceStorage persistence,
                                  VectorLiteProperties properties) {
        this(localVectorEngine, embeddingProvider, null, persistence, properties);
    }

    public VectorEngineClientImpl(LocalVectorEngine localVectorEngine,
                                  EmbeddingProvider embeddingProvider,
                                  EmbeddingService embeddingService,
                                  VectorPersistenceStorage persistence,
                                  VectorLiteProperties properties) {
        this.localVectorEngine = localVectorEngine;
        this.embeddingProvider = embeddingProvider;
        this.embeddingService = embeddingService;
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
     * <p>若文档未携带 vector 但携带 text，则按 Store 绑定的 Embedding 模型自动向量化。
     */
    @Override
    public void upsert(String storeName, VectorDocument document) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        ensureEmbedded(store, document);
        store.upsert(document);
    }

    /**
     * 批量写入/更新向量文档。
     * <p>对未携带 vector 的文档按 Store 绑定的 Embedding 模型自动向量化。
     */
    @Override
    public void upsertBatch(String storeName, List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        // 收集需要 embedding 的文档，按 model 分组后批量调 EmbeddingService.embedTexts（按 batchSize 切批）
        List<Integer> needsEmbedIdx = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            VectorDocument d = documents.get(i);
            if (d == null) continue;
            if (d.getVector() == null || d.getVector().length == 0) {
                if (d.getText() == null || d.getText().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Document must carry either a non-empty 'vector' or a non-empty 'text' field.");
                }
                needsEmbedIdx.add(i);
            }
        }
        if (!needsEmbedIdx.isEmpty()) {
            String modelName = resolveStoreModel(store);
            ensureEmbeddingReady(modelName);
            int targetDim = store.getDefinition().getDimension();
            // 按 batchSize 走 EmbeddingService 批量（用统一的 EmbeddingService.embedTexts 走 batchSize）
            // 若没注入 embeddingService,降级走 embeddingProvider 逐条 embed
            List<List<Float>> vectors;
            if (embeddingService != null) {
                List<String> texts = new ArrayList<>(needsEmbedIdx.size());
                for (int idx : needsEmbedIdx) {
                    texts.add(documents.get(idx).getText());
                }
                String resolvedVersion = embeddingService.resolveVersion(modelName,
                        store.getDefinition().getEmbeddingModelVersion());
                vectors = embeddingService.embedTexts(modelName, resolvedVersion, texts);
            } else {
                String resolvedVersion = store.getDefinition().getEmbeddingModelVersion();
                if (embeddingService != null) {
                    resolvedVersion = embeddingService.resolveVersion(modelName, resolvedVersion);
                }
                vectors = new ArrayList<>(needsEmbedIdx.size());
                for (int idx : needsEmbedIdx) {
                    List<Float> v = embeddingProvider.embed(modelName, resolvedVersion,
                            documents.get(idx).getText(), targetDim);
                    vectors.add(v);
                }
            }
            for (int j = 0; j < needsEmbedIdx.size(); j++) {
                VectorDocument d = documents.get(needsEmbedIdx.get(j));
                List<Float> v = vectors.get(j);
                float[] arr = new float[v.size()];
                for (int k = 0; k < v.size(); k++) arr[k] = v.get(k);
                d.setVector(arr);
            }
        }
        for (VectorDocument doc : documents) {
            store.upsert(doc);
        }
    }

    /**
     * 解析 store 绑定的 embedding model：优先 store 上的 model,其次 default model。
     */
    private String resolveStoreModel(LocalVectorStore store) {
        String modelName = store.getDefinition().getEmbeddingModel();
        if (modelName == null || modelName.isEmpty()) {
            modelName = properties.getEmbedding().getDefaultModel();
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new IllegalStateException(
                    "No embedding model bound to store [" + store.getDefinition().getStoreName()
                            + "] and no default model configured.");
        }
        return modelName;
    }

    /**
     * 检查 EmbeddingProvider / EmbeddingService 是否就绪。
     */
    private void ensureEmbeddingReady(String modelName) {
        if (embeddingProvider == null) {
            throw new IllegalStateException("No EmbeddingProvider available for text embedding.");
        }
        if (embeddingService != null && !embeddingService.hasModel(modelName)) {
            throw new IllegalStateException(
                    "Embedding model [" + modelName + "] is not configured.");
        }
    }

    /**
     * 若文档未提供 vector，则根据 Store 绑定的 Embedding 模型自动补全。
     * <p>调用规则：
     * <ul>
     *   <li>doc.vector 已存在 → 不调 Embedding</li>
     *   <li>doc.text 为空 → 抛错（必须有 vector 或 text 之一）</li>
     *   <li>Store 未绑定 embeddingModel → 抛错</li>
     *   <li>无 EmbeddingProvider → 抛错</li>
     * </ul>
     */
    private void ensureEmbedded(LocalVectorStore store, VectorDocument document) {
        if (document.getVector() != null && document.getVector().length > 0) {
            return;  // 已有向量，无需 Embedding
        }
        if (document.getText() == null || document.getText().isEmpty()) {
            throw new IllegalArgumentException(
                    "Document must carry either a non-empty 'vector' or a non-empty 'text' field.");
        }
        String modelName = resolveStoreModel(store);
        ensureEmbeddingReady(modelName);
        int targetDim = store.getDefinition().getDimension();
        String requestedVersion = store.getDefinition().getEmbeddingModelVersion();
        String resolvedVersion = embeddingService != null
                ? embeddingService.resolveVersion(modelName, requestedVersion)
                : requestedVersion;
        List<Float> floatList = embeddingProvider.embed(modelName, resolvedVersion, document.getText(), targetDim);
        if (floatList == null || floatList.isEmpty()) {
            throw new IllegalStateException(
                    "Embedding provider returned empty vector for model: " + modelName);
        }
        float[] vector = new float[floatList.size()];
        for (int i = 0; i < floatList.size(); i++) {
            vector[i] = floatList.get(i);
        }
        document.setVector(vector);
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
        String modelName = resolveStoreModel(store);
        ensureEmbeddingReady(modelName);

        // 调用 Embedding 模型计算查询文本的向量，传入 store 期望的维度
        int targetDim = store.getDefinition().getDimension();
        String requestedVersion = store.getDefinition().getEmbeddingModelVersion();
        String resolvedVersion = embeddingService != null
                ? embeddingService.resolveVersion(modelName, requestedVersion)
                : requestedVersion;
        List<Float> floatList = embeddingProvider.embed(modelName, resolvedVersion, request.getQueryText(), targetDim);
        if (floatList == null || floatList.isEmpty()) {
            throw new IllegalStateException(
                    "Embedding provider returned empty vector for model: " + modelName);
        }
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
    public VectorDocumentPage listDocuments(String storeName, int page, int size, boolean includeVector) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);

        // 文档列表用于管理/调试页面，不应默认把高维向量传回客户端。
        // LocalVectorStore 的分页约定从第 1 页开始，并会在内部跳过已删除文档；
        // 这里先归一化参数，再补齐 API 层要求的分页元数据。
        int safePage = Math.max(1, page);
        int safeSize = size > 0 ? size : 20;
        List<VectorDocument> items = store.listDocuments(safePage, safeSize, includeVector);
        return new VectorDocumentPage(items, safePage, safeSize, store.getActiveCount());
    }

    /**
     * 获取指定 Store 的容量与配置状态统计信息。
     * <p>附带 {@code storageSource} 字段，标识数据来源：
     * <ul>
     *   <li>{@code OSS} — 当前持久化后端是 {@link veclite.persistence.OssSnapshotStorage}</li>
     *   <li>{@code LOCAL} — 当前持久化后端是 {@link veclite.persistence.SnapshotFileStorage}</li>
     *   <li>{@code IN_MEMORY} — 当前持久化后端是 {@link veclite.persistence.NoopVectorPersistenceStorage}</li>
     *   <li>{@code UNKNOWN} — 异常兜底</li>
     * </ul>
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

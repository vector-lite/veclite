package veclite.engine;

import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreManager;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingService;
import veclite.model.VectorStoreStats;
import veclite.persistence.meta.VectorMetadataRepository;
import veclite.persistence.meta.VectorStoreMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class LocalVectorEngine implements VectorStoreManager {

    private final VectorLiteProperties properties;
    private final EmbeddingService embeddingService;
    private final VectorMetadataRepository metadataRepository;
    private final Map<String, LocalVectorStore> stores = new ConcurrentHashMap<>();

    public LocalVectorEngine() {
        this(null, null, null);
    }

    public LocalVectorEngine(VectorLiteProperties properties) {
        this(properties, null, null);
    }

    public LocalVectorEngine(VectorLiteProperties properties, EmbeddingService embeddingService) {
        this(properties, embeddingService, null);
    }

    public LocalVectorEngine(VectorLiteProperties properties,
                             EmbeddingService embeddingService,
                             VectorMetadataRepository metadataRepository) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.metadataRepository = metadataRepository;
    }

    /**
     * 创建向量库 Store。
     * <p>
     * Embedding 模型绑定（模型名 + 版本）在创建时校验并固化：
     * <ul>
     *   <li>新 Store：绑定模型必须已在 veclite.embedding.models 中配置（fail-fast）；未指定版本时归一化为模型配置的默认版本</li>
     *   <li>已存在的 Store：绑定一致则幂等返回；不一致（含从无绑定改为有绑定）抛出异常，绑定创建后不可变更</li>
     * </ul>
     */
    @Override
    public void createStore(String storeName, VectorStoreDefinition definition) {
        if (storeName == null || definition == null) {
            throw new IllegalArgumentException("Store name and definition must not be null");
        }
        definition.setStoreName(storeName);

        stores.compute(storeName, (name, existing) -> {
            if (existing != null) {
                // 已存在的 Store：优先校验绑定不可变（即使请求的模型未配置，也归属为不可变冲突）
                VectorStoreDefinition current = existing.getDefinition();
                String reqModel = definition.getEmbeddingModel();
                String reqVersion = normalizeVersionForCompare(reqModel, definition.getEmbeddingModelVersion());
                if (!Objects.equals(current.getEmbeddingModel(), reqModel)
                        || !Objects.equals(current.getEmbeddingModelVersion(), reqVersion)) {
                    throw new ImmutableEmbeddingBindingException(storeName,
                            current.getEmbeddingModel(), current.getEmbeddingModelVersion(),
                            reqModel, reqVersion);
                }
                // 绑定一致：幂等返回已有 Store
                return existing;
            }
            // 新建 Store：绑定模型必须已配置（fail-fast），并将版本归一化后固化到定义上
            if (definition.getEmbeddingModel() != null && embeddingService != null) {
                embeddingService.validateBinding(definition.getEmbeddingModel(), definition.getEmbeddingModelVersion());
                definition.setEmbeddingModelVersion(
                        embeddingService.resolveVersion(definition.getEmbeddingModel(), definition.getEmbeddingModelVersion()));
            }
            LocalVectorStore store = new LocalVectorStore(definition, properties);
            // v2.4 hybrid persistence: 元数据写 PG（如已配置）
            if (metadataRepository != null) {
                metadataRepository.save(toMetadata(store));
            }
            return store;
        });
    }

    /**
     * 不可变比较用的版本归一化：仅当模型已配置时可归一化，否则保留原值参与比较。
     */
    private String normalizeVersionForCompare(String model, String version) {
        if (model != null && embeddingService != null && embeddingService.hasModel(model)) {
            return embeddingService.resolveVersion(model, version);
        }
        return version;
    }

    @Override
    public boolean hasStore(String storeName) {
        return storeName != null && stores.containsKey(storeName);
    }

    @Override
    public void dropStore(String storeName) {
        if (storeName != null) {
            stores.remove(storeName);
            if (metadataRepository != null) {
                metadataRepository.deleteByName(storeName);
            }
        }
    }

    @Override
    public VectorStoreStats stats(String storeName) {
        LocalVectorStore store = getStore(storeName);
        return store.getStats();
    }

    @Override
    public List<String> listStores() {
        return new ArrayList<>(stores.keySet());
    }

    public LocalVectorStore getStore(String storeName) {
        LocalVectorStore store = stores.get(storeName);
        if (store == null) {
            throw new IllegalArgumentException("Vector store not found: " + storeName);
        }
        return store;
    }

    public Optional<VectorMetadataRepository> getMetadataRepository() {
        return Optional.ofNullable(metadataRepository);
    }

    private VectorStoreMetadata toMetadata(LocalVectorStore store) {
        VectorStoreDefinition d = store.getDefinition();
        VectorStoreMetadata m = new VectorStoreMetadata();
        m.setStoreName(d.getStoreName());
        m.setDimension(d.getDimension());
        m.setMetric(d.getMetric());
        m.setMaxCapacity(d.getMaxCapacity());
        m.setEmbeddingModel(d.getEmbeddingModel());
        m.setEmbeddingModelVersion(d.getEmbeddingModelVersion());
        m.setQuantization(d.getQuantization());
        m.setIndexedMetadataFields(d.getIndexedMetadataFields());
        m.setActiveCount(0);
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        return m;
    }

    /**
     * 尝试变更已创建 Store 的 Embedding 绑定时抛出（绑定创建后不可变）。
     */
    public static class ImmutableEmbeddingBindingException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ImmutableEmbeddingBindingException(String storeName,
                                                  String currentModel, String currentVersion,
                                                  String requestedModel, String requestedVersion) {
            super("Embedding binding of store [" + storeName + "] is immutable after creation. "
                    + "Current: [" + currentModel + ":" + currentVersion + "], "
                    + "requested: [" + requestedModel + ":" + requestedVersion + "]. "
                    + "Drop the store and re-create it to use a different embedding model.");
        }
    }
}

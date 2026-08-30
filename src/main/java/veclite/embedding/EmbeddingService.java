package veclite.embedding;

import veclite.api.EmbeddingProvider;
import veclite.config.VectorLiteProperties;
import veclite.model.EmbeddingModelInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Embedding 接口管理服务。
 * <p>
 * 模型配置<b>只由数据库维护</b>（经 {@link EmbeddingModelRegistry}，MongoDB 模式下持久化到
 * {@code veclite_embedding_model} 集合），不再从 application.yml 读取。
 * 提供：数据源增删改查的查询侧、绑定校验、版本归一化，以及按 batchSize 分批的文本向量化调用。
 * <p>
 * Store 与 Embedding 模型（名称+版本）的绑定关系在创建时经由本服务校验并固化，创建后不可变更。
 */
public class EmbeddingService {

    private final EmbeddingProvider provider;
    private final EmbeddingModelRegistry registry;

    public EmbeddingService(EmbeddingProvider provider, EmbeddingModelRegistry registry) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    // -------------------- 模型管理 --------------------

    /** 列出全部 Embedding 数据源（每个（名称， 版本）一行）。 */
    public List<EmbeddingModelInfo> listModels() {
        List<EmbeddingModelInfo> result = new ArrayList<>();
        EmbeddingModelRef defaultRef = registry.defaultRef();
        for (VectorLiteProperties.ModelConfig config : registry.effectiveList()) {
            boolean isDefault = isDefaultRow(config, defaultRef);
            EmbeddingModelInfo info = toModelInfo(config, isDefault);
            result.add(info);
        }
        return result;
    }

    /** 查询指定名称的模型配置（同名称多版本时返回主版本行），不存在时返回 null。 */
    public EmbeddingModelInfo getModel(String modelName) {
        if (modelName == null) {
            return null;
        }
        VectorLiteProperties.ModelConfig config = registry.find(modelName, null);
        if (config == null) {
            return null;
        }
        EmbeddingModelRef defaultRef = registry.defaultRef();
        return toModelInfo(config, isDefaultRow(config, defaultRef));
    }

    /** 判断指定名称是否已配置（任一版本）。 */
    public boolean hasModel(String modelName) {
        return registry.hasName(modelName);
    }

    /** 当前默认模型名称，未设置时返回 null。 */
    public String defaultModelName() {
        EmbeddingModelRef ref = registry.defaultRef();
        return ref != null ? ref.name() : null;
    }

    /**
     * 校验模型绑定是否可用：模型（名称+解析后版本）必须已配置且 Provider 存在。
     * 用于 Store 创建时的 fail-fast 校验。
     */
    public void validateBinding(String modelName, String modelVersion) {
        if (modelName == null) {
            return;
        }
        if (!hasModel(modelName)) {
            throw new IllegalArgumentException(
                    "Embedding model [" + modelName + "] is not configured. "
                            + "Please add it via the data source management API or page first.");
        }
        if (provider == null) {
            throw new IllegalStateException(
                    "No EmbeddingProvider available for embedding model [" + modelName + "].");
        }
    }

    /**
     * 版本归一化：未显式指定版本时，回退到该名称的主版本；主版本也不存在时使用 "1"。
     * 绑定关系以归一化后的（模型名, 版本）为准，保证不可变校验的确定性。
     */
    public String resolveVersion(String modelName, String requestedVersion) {
        if (modelName == null) {
            return null;
        }
        if (requestedVersion != null && !requestedVersion.isEmpty()) {
            return requestedVersion;
        }
        String primary = registry.primaryVersion(modelName);
        return primary != null && !primary.isEmpty() ? primary : "1";
    }

    // -------------------- 向量化调用 --------------------

    /**
     * 单条文本向量化。
     */
    public List<Float> embed(String modelName, String modelVersion, String text) {
        if (provider == null) {
            throw new IllegalStateException("No EmbeddingProvider available for embedding model [" + modelName + "].");
        }
        return provider.embed(modelName, resolveVersion(modelName, modelVersion), text);
    }

    /**
     * 批量文本向量化：按模型配置的 batchSize 分批调用 Embedding 服务。
     *
     * @return 与输入文本顺序一致的向量列表
     */
    public List<List<Float>> embedTexts(String modelName, String modelVersion, List<String> texts) {
        if (provider == null) {
            throw new IllegalStateException("No EmbeddingProvider available for embedding model [" + modelName + "].");
        }
        String resolvedVersion = resolveVersion(modelName, modelVersion);
        int batchSize = resolveBatchSize(modelName, resolvedVersion);
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            List<String> chunk = texts.subList(from, to);
            List<List<Float>> embedded = provider.embedBatch(modelName, resolvedVersion, new ArrayList<>(chunk));
            if (embedded == null || embedded.size() != chunk.size()) {
                throw new IllegalStateException("Embedding service returned "
                        + (embedded == null ? 0 : embedded.size()) + " vectors for " + chunk.size()
                        + " input texts (model: " + modelName + ", version: " + resolvedVersion + ").");
            }
            result.addAll(embedded);
        }
        return result;
    }

    // -------------------- 内部工具 --------------------

    private boolean isDefaultRow(VectorLiteProperties.ModelConfig config, EmbeddingModelRef defaultRef) {
        return defaultRef != null && defaultRef.name().equals(config.getName())
                && (defaultRef.version() == null || defaultRef.version().equals(config.getVersion()));
    }

    private VectorLiteProperties.ModelConfig findConfig(String modelName, String version) {
        return registry.find(modelName, version);
    }

    private int resolveBatchSize(String modelName, String version) {
        VectorLiteProperties.ModelConfig config = findConfig(modelName, version);
        int batchSize = config != null ? config.getBatchSize() : 0;
        return batchSize > 0 ? batchSize : 1;
    }

    private EmbeddingModelInfo toModelInfo(VectorLiteProperties.ModelConfig config, boolean isDefault) {
        EmbeddingModelInfo info = new EmbeddingModelInfo();
        info.setName(config.getName());
        info.setVersion(config.getVersion());
        info.setProvider(config.getProvider());
        info.setUrl(config.getUrl());
        info.setTimeoutMillis(config.getTimeoutMillis());
        info.setBatchSize(config.getBatchSize());
        info.setDefaultModel(isDefault);
        return info;
    }
}

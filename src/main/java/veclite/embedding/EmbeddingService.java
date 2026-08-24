package veclite.embedding;

import veclite.api.EmbeddingProvider;
import veclite.config.VectorLiteProperties;
import veclite.model.EmbeddingModelInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding 接口管理服务。
 * <p>
 * 统一管理 {@code veclite.embedding.models} 下配置的 Embedding 服务端点（重要参数：模型名称 + 版本），
 * 提供：模型查询、绑定校验、版本归一化，以及按 batchSize 分批的文本向量化调用。
 * <p>
 * Store 与 Embedding 模型（名称+版本）的绑定关系在创建时经由本服务校验并固化，创建后不可变更。
 */
public class EmbeddingService {

    private final EmbeddingProvider provider;
    private final VectorLiteProperties properties;

    public EmbeddingService(EmbeddingProvider provider, VectorLiteProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    // -------------------- 模型管理 --------------------

    /**
     * 列出所有已配置的 Embedding 模型端点信息。
     */
    public List<EmbeddingModelInfo> listModels() {
        List<EmbeddingModelInfo> result = new ArrayList<>();
        Map<String, VectorLiteProperties.ModelConfig> models = modelConfigs();
        String defaultModel = properties.getEmbedding().getDefaultModel();
        if (models != null) {
            models.forEach((name, config) -> result.add(toModelInfo(name, config, name.equals(defaultModel))));
        }
        return result;
    }

    /**
     * 查询指定名称的模型配置，不存在时返回 null。
     */
    public EmbeddingModelInfo getModel(String modelName) {
        if (modelName == null) {
            return null;
        }
        VectorLiteProperties.ModelConfig config = modelConfigs().get(modelName);
        if (config == null) {
            return null;
        }
        return toModelInfo(modelName, config, modelName.equals(properties.getEmbedding().getDefaultModel()));
    }

    /**
     * 判断指定模型是否已配置。
     */
    public boolean hasModel(String modelName) {
        return modelName != null && modelConfigs().containsKey(modelName);
    }

    /**
     * 校验模型绑定是否可用：模型必须已配置且 Provider 存在。
     * 用于 Store 创建时的 fail-fast 校验。
     */
    public void validateBinding(String modelName, String modelVersion) {
        if (modelName == null) {
            return;
        }
        if (!hasModel(modelName)) {
            throw new IllegalArgumentException(
                    "Embedding model [" + modelName + "] is not configured. "
                            + "Please add it under veclite.embedding.models before binding it to a store.");
        }
        if (provider == null) {
            throw new IllegalStateException(
                    "No EmbeddingProvider available for embedding model [" + modelName + "].");
        }
    }

    /**
     * 版本归一化：未显式指定版本时，回退到模型配置中的默认版本；未配置版本时使用 "1"。
     * 绑定关系以归一化后的（模型名, 版本）为准，保证不可变校验的确定性。
     */
    public String resolveVersion(String modelName, String requestedVersion) {
        if (modelName == null) {
            return null;
        }
        if (requestedVersion != null && !requestedVersion.isEmpty()) {
            return requestedVersion;
        }
        VectorLiteProperties.ModelConfig config = modelConfigs().get(modelName);
        if (config != null && config.getVersion() != null && !config.getVersion().isEmpty()) {
            return config.getVersion();
        }
        return "1";
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
        int batchSize = resolveBatchSize(modelName);
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

    private Map<String, VectorLiteProperties.ModelConfig> modelConfigs() {
        Map<String, VectorLiteProperties.ModelConfig> models = properties.getEmbedding().getModels();
        return models == null ? Map.of() : models;
    }

    private int resolveBatchSize(String modelName) {
        VectorLiteProperties.ModelConfig config = modelConfigs().get(modelName);
        int batchSize = config != null ? config.getBatchSize() : 0;
        return batchSize > 0 ? batchSize : 8;
    }

    private EmbeddingModelInfo toModelInfo(String name, VectorLiteProperties.ModelConfig config, boolean isDefault) {
        EmbeddingModelInfo info = new EmbeddingModelInfo();
        info.setName(name);
        info.setVersion(config.getVersion());
        info.setProvider(config.getProvider());
        info.setUrl(config.getUrl());
        info.setTimeoutMillis(config.getTimeoutMillis());
        info.setBatchSize(config.getBatchSize());
        info.setDefaultModel(isDefault);
        return info;
    }
}

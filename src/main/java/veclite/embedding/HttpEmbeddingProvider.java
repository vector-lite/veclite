package veclite.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import veclite.api.EmbeddingProvider;
import veclite.config.VectorLiteProperties;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * 通过 HTTP 调用远程 Embedding 服务的适配器。
 * <p>服务端允许返回三种兼容格式，格式识别与解析由策略工厂负责，
 * 使网络请求流程与响应解析职责保持隔离。</p>
 */
public class HttpEmbeddingProvider implements EmbeddingProvider {

    private static final String HTTP_METHOD = "POST";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final int HTTP_OK = 200;

    private final VectorLiteProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建 HTTP Embedding 提供者。
     *
     * @param properties 向量库配置，必须包含 Embedding 模型配置
     */
    public HttpEmbeddingProvider(VectorLiteProperties properties) {
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public List<Float> embed(String modelName, String modelVersion, String text, int dimension) {
        List<List<Float>> results = embedBatch(modelName, modelVersion, Collections.singletonList(text), dimension);
        if (results != null && !results.isEmpty()) {
            return results.get(0);
        }
        throw new IllegalStateException("Empty embedding response for model: " + modelName);
    }

    /** {@inheritDoc} */
    @Override
    public List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts, int dimension) {
        VectorLiteProperties.ModelConfig modelConfig = resolveModelConfig(modelName);
        String urlString = modelConfig.getUrl();
        if (urlString == null || urlString.isEmpty()) {
            throw new IllegalArgumentException("No URL configured for embedding model: " + modelName);
        }

        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(HTTP_METHOD);
            conn.setRequestProperty("Content-Type", JSON_CONTENT_TYPE);
            if (modelConfig.getApiKey() != null && !modelConfig.getApiKey().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + modelConfig.getApiKey());
            }
            conn.setConnectTimeout(modelConfig.getTimeoutMillis());
            conn.setReadTimeout(modelConfig.getTimeoutMillis());
            conn.setDoOutput(true);

            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", modelName);
            reqBody.put("version", modelVersion != null ? modelVersion : modelConfig.getVersion());
            reqBody.put("input", texts);
            // 维度优先级：调用方传入 > yml ModelConfig 配置
            int effectiveDim = dimension > 0 ? dimension : modelConfig.getDimension();
            if (effectiveDim > 0) {
                reqBody.put("dimension", effectiveDim);
            }

            byte[] jsonBytes = objectMapper.writeValueAsBytes(reqBody);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HTTP_OK) {
                throw new RuntimeException("HTTP embedding request failed with status code: " + responseCode);
            }

            JsonNode root = objectMapper.readTree(conn.getInputStream());
            List<List<Float>> embeddings = EmbeddingResponseParserFactory
                    .forResponse(root)
                    .parse(root);

            if (embeddings.isEmpty()) {
                throw new IllegalStateException("Failed to parse vector embedding from HTTP response");
            }
            return embeddings;
        } catch (Exception e) {
            throw new RuntimeException("HTTP Embedding request failed for model [" + modelName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 解析请求模型配置；请求模型不存在时沿用既有默认模型回退规则。
     *
     * @param modelName 请求使用的模型名
     * @return 可用于发起 HTTP 请求的模型配置
     * @throws IllegalArgumentException 未找到请求模型及默认模型配置时抛出
     */
    private VectorLiteProperties.ModelConfig resolveModelConfig(String modelName) {
        Map<String, VectorLiteProperties.ModelConfig> models = properties.getEmbedding().getModels();
        if (models != null && models.containsKey(modelName)) {
            return models.get(modelName);
        }
        // 请求的模型未命中时，回退到默认模型名对应的配置（defaultModel 是名称，不是 URL）
        String defaultModelName = properties.getEmbedding().getDefaultModel();
        if (defaultModelName != null && !defaultModelName.equals(modelName)
                && models != null && models.containsKey(defaultModelName)) {
            return models.get(defaultModelName);
        }
        throw new IllegalArgumentException(
                "No configuration found for embedding model [" + modelName + "]. "
                        + "Please configure it under veclite.embedding.models with a valid URL.");
    }

    /** 远程服务响应的顶层形态。 */
    private enum ResponseShape { DATA, SINGLE, ARRAY, UNKNOWN }

    /** 响应解析策略，单一职责地将一种 JSON 形态转换为向量列表。 */
    private interface EmbeddingResponseParser {
        List<List<Float>> parse(JsonNode root);
    }

    /** 根据响应形态创建解析策略的工厂。 */
    private static final class EmbeddingResponseParserFactory {
        private EmbeddingResponseParserFactory() {
        }

        static EmbeddingResponseParser forResponse(JsonNode root) {
            ResponseShape shape = classify(root);
            return switch (shape) {
                case DATA -> HttpEmbeddingProvider::parseDataResponse;
                case SINGLE -> HttpEmbeddingProvider::parseSingleResponse;
                case ARRAY -> HttpEmbeddingProvider::parseArrayResponse;
                case UNKNOWN -> ignored -> List.of();
            };
        }

        private static ResponseShape classify(JsonNode root) {
            if (root != null && root.has("data") && root.get("data").isArray()) {
                return ResponseShape.DATA;
            }
            if (root != null && root.has("embedding") && root.get("embedding").isArray()) {
                return ResponseShape.SINGLE;
            }
            return root != null && root.isArray() ? ResponseShape.ARRAY : ResponseShape.UNKNOWN;
        }
    }

    /** 解析 OpenAI 兼容的 {@code data} 数组响应。 */
    private static List<List<Float>> parseDataResponse(JsonNode root) {
        List<List<Float>> embeddings = new ArrayList<>();
        for (JsonNode item : root.get("data")) {
            JsonNode vector = item.has("embedding") && item.get("embedding").isArray()
                    ? item.get("embedding") : item.isArray() ? item : null;
            if (vector != null) {
                embeddings.add(parseVectorNodeStatic(vector));
            }
        }
        return embeddings;
    }

    /** 解析单个 {@code embedding} 字段响应。 */
    private static List<List<Float>> parseSingleResponse(JsonNode root) {
        return List.of(parseVectorNodeStatic(root.get("embedding")));
    }

    /** 解析顶层为二维数组的响应。 */
    private static List<List<Float>> parseArrayResponse(JsonNode root) {
        List<List<Float>> embeddings = new ArrayList<>();
        for (JsonNode item : root) {
            if (item.isArray()) {
                embeddings.add(parseVectorNodeStatic(item));
            }
        }
        return embeddings;
    }

    private static List<Float> parseVectorNodeStatic(JsonNode arrayNode) {
        List<Float> vector = new ArrayList<>(arrayNode.size());
        for (JsonNode value : arrayNode) {
            vector.add((float) value.asDouble());
        }
        return vector;
    }
}

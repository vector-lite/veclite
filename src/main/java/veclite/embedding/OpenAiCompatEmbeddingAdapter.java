package veclite.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议适配器（默认协议）。
 * <p>
 * 请求体 {@code {"model":..., "version":..., "input":[texts]}}，
 * 响应兼容三种常见形态：OpenAI 的 {@code data[].embedding}、
 * 单字段 {@code embedding}、顶层二维数组。
 */
public final class OpenAiCompatEmbeddingAdapter implements EmbeddingHttpAdapter {

    static final OpenAiCompatEmbeddingAdapter INSTANCE = new OpenAiCompatEmbeddingAdapter();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OpenAiCompatEmbeddingAdapter() {
    }

    @Override
    public List<byte[]> buildRequests(String modelName, String modelVersion, List<String> texts, int dimension) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("version", modelVersion);
        body.put("input", texts);
        // OpenAI 兼容协议用 dimensions（复数）表达降维请求；不指定时由服务端决定维度
        if (dimension > 0) {
            body.put("dimensions", dimension);
        }
        return List.of(toBytes(body));
    }

    @Override
    public List<List<Float>> parseResponse(JsonNode root) {
        if (root != null && root.has("data") && root.get("data").isArray()) {
            return parseEach(root.get("data"), item ->
                    item.has("embedding") && item.get("embedding").isArray() ? item.get("embedding") : item);
        }
        if (root != null && root.has("embedding") && root.get("embedding").isArray()) {
            return List.of(parseVector(root.get("embedding")));
        }
        if (root != null && root.isArray()) {
            return parseEach(root, item -> item);
        }
        return List.of();
    }

    private List<List<Float>> parseEach(JsonNode items, java.util.function.Function<JsonNode, JsonNode> vectorOf) {
        List<List<Float>> embeddings = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode vector = item.isArray() ? item : vectorOf.apply(item);
            if (vector != null && vector.isArray()) {
                embeddings.add(parseVector(vector));
            }
        }
        return embeddings;
    }

    private List<Float> parseVector(JsonNode arrayNode) {
        List<Float> vector = new ArrayList<>(arrayNode.size());
        for (JsonNode value : arrayNode) {
            vector.add((float) value.asDouble());
        }
        return vector;
    }

    private byte[] toBytes(Map<String, Object> body) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize embedding request body", e);
        }
    }

    @Override
    public String protocol() {
        return "openai";
    }
}

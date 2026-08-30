package veclite.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 老接口 {@code /api/embeddings} 协议适配器（provider = "ollama"）。
 * <p>
 * 请求体 {@code {"model":..., "prompt": "<单条文本>"}}，每条文本一个请求，
 * 响应体 {@code {"embedding":[...]}}。
 * <p>
 * 注意：该接口对不认识的请求字段保持静默——错误地发送 {@code input} 数组会得到
 * {@code {"embedding":[]}}（空向量）而非报错，因此调用方必须对空向量做显式校验。
 */
public final class OllamaLegacyEmbeddingAdapter implements EmbeddingHttpAdapter {

    static final OllamaLegacyEmbeddingAdapter INSTANCE = new OllamaLegacyEmbeddingAdapter();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OllamaLegacyEmbeddingAdapter() {
    }

    @Override
    public List<byte[]> buildRequests(String modelName, String modelVersion, List<String> texts) {
        List<byte[]> bodies = new ArrayList<>(texts.size());
        for (String text : texts) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("prompt", text);
            bodies.add(toBytes(body));
        }
        return bodies;
    }

    @Override
    public List<List<Float>> parseResponse(JsonNode root) {
        JsonNode embedding = root != null ? root.get("embedding") : null;
        if (embedding == null || !embedding.isArray()) {
            return List.of();
        }
        List<Float> vector = new ArrayList<>(embedding.size());
        for (JsonNode value : embedding) {
            vector.add((float) value.asDouble());
        }
        return List.of(vector);
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
        return "ollama";
    }
}

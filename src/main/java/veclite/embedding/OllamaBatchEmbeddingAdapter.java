package veclite.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 新接口 {@code /api/embed} 协议适配器（provider = "ollama-embed"）。
 * <p>
 * 请求体 {@code {"model":..., "input":[texts]}} 原生批量，一次请求覆盖全部文本，
 * 响应体 {@code {"embeddings":[[...], [...]]}}，向量顺序与输入顺序一致。
 */
public final class OllamaBatchEmbeddingAdapter implements EmbeddingHttpAdapter {

    static final OllamaBatchEmbeddingAdapter INSTANCE = new OllamaBatchEmbeddingAdapter();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OllamaBatchEmbeddingAdapter() {
    }

    @Override
    public List<byte[]> buildRequests(String modelName, String modelVersion, List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", texts);
        return List.of(toBytes(body));
    }

    @Override
    public List<List<Float>> parseResponse(JsonNode root) {
        JsonNode embeddings = root != null ? root.get("embeddings") : null;
        if (embeddings == null || !embeddings.isArray()) {
            return List.of();
        }
        List<List<Float>> result = new ArrayList<>(embeddings.size());
        for (JsonNode vectorNode : embeddings) {
            if (!vectorNode.isArray()) {
                return List.of();
            }
            List<Float> vector = new ArrayList<>(vectorNode.size());
            for (JsonNode value : vectorNode) {
                vector.add((float) value.asDouble());
            }
            result.add(vector);
        }
        return result;
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
        return "ollama-embed";
    }
}

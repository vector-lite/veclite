package com.hexin.vector.lite.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hexin.vector.lite.api.EmbeddingProvider;
import com.hexin.vector.lite.config.VectorLiteProperties;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class HttpEmbeddingProvider implements EmbeddingProvider {

    private final VectorLiteProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpEmbeddingProvider(VectorLiteProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Float> embed(String modelName, String modelVersion, String text) {
        List<List<Float>> results = embedBatch(modelName, modelVersion, Collections.singletonList(text));
        if (results != null && !results.isEmpty()) {
            return results.get(0);
        }
        throw new IllegalStateException("Empty embedding response for model: " + modelName);
    }

    @Override
    public List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts) {
        VectorLiteProperties.ModelConfig modelConfig = resolveModelConfig(modelName);
        String urlString = modelConfig.getUrl();
        if (urlString == null || urlString.isEmpty()) {
            throw new IllegalArgumentException("No URL configured for embedding model: " + modelName);
        }

        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(modelConfig.getTimeoutMillis());
            conn.setReadTimeout(modelConfig.getTimeoutMillis());
            conn.setDoOutput(true);

            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", modelName);
            reqBody.put("version", modelVersion != null ? modelVersion : modelConfig.getVersion());
            reqBody.put("input", texts);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(reqBody);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new RuntimeException("HTTP embedding request failed with status code: " + responseCode);
            }

            JsonNode root = objectMapper.readTree(conn.getInputStream());
            List<List<Float>> embeddings = new ArrayList<>();

            if (root.has("data") && root.get("data").isArray()) {
                for (JsonNode item : root.get("data")) {
                    if (item.has("embedding") && item.get("embedding").isArray()) {
                        embeddings.add(parseVectorNode(item.get("embedding")));
                    } else if (item.isArray()) {
                        embeddings.add(parseVectorNode(item));
                    }
                }
            } else if (root.has("embedding") && root.get("embedding").isArray()) {
                embeddings.add(parseVectorNode(root.get("embedding")));
            } else if (root.isArray()) {
                for (JsonNode item : root) {
                    if (item.isArray()) {
                        embeddings.add(parseVectorNode(item));
                    }
                }
            }

            if (embeddings.isEmpty()) {
                throw new IllegalStateException("Failed to parse vector embedding from HTTP response");
            }
            return embeddings;
        } catch (Exception e) {
            throw new RuntimeException("HTTP Embedding request failed for model [" + modelName + "]: " + e.getMessage(), e);
        }
    }

    private VectorLiteProperties.ModelConfig resolveModelConfig(String modelName) {
        Map<String, VectorLiteProperties.ModelConfig> models = properties.getEmbedding().getModels();
        if (models != null && models.containsKey(modelName)) {
            return models.get(modelName);
        }
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName(modelName);
        config.setUrl(properties.getEmbedding().getDefaultModel());
        return config;
    }

    private List<Float> parseVectorNode(JsonNode arrayNode) {
        List<Float> vector = new ArrayList<>(arrayNode.size());
        for (JsonNode val : arrayNode) {
            vector.add((float) val.asDouble());
        }
        return vector;
    }
}

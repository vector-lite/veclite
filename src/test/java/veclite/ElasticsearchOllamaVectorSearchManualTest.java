package veclite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 手动验证 Ollama embedding + Elasticsearch KNN 检索。
 *
 * <p>默认禁用，不会在常规 {@code ./gradlew test} 时访问外部服务。运行示例：</p>
 * <pre>
 * ./gradlew test --tests 'veclite.ElasticsearchOllamaVectorSearchManualTest' \
 *   -Des.url=http://100.66.1.2:1292 \
 *   -Des.username=elastic -Des.password='your-password' \
 *   -Des.index=veclite_precision_hnsw_20260815_204347 \
 *   -Des.manual.enabled=true
 * </pre>
 */
public class ElasticsearchOllamaVectorSearchManualTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String OLLAMA_URL = "http://localhost:11434/api/embed";
    private static final String OLLAMA_MODEL = "bge-small-zh:latest";
    private static final String ES_URL = "http://100.66.1.2:1292";
    private static final String ES_USERNAME = "elastic";
    private static final String ES_PASSWORD = "WpG7iZNJW=i3QU0kRT6D";
    private static final String ES_INDEX = "veclite_precision_hnsw_20260815_204347";
//    private static final String ES_INDEX = "veclite_precision_bbq_hnsw_20260815_204347";
    private static final String ES_VECTOR_FIELD = "vector";

    @Test
    void searchesTopKFromText() throws Exception {
        List<ElasticsearchHit> hits = search("AI 眼镜芯片订单放量", 10);

        assertFalse(hits.isEmpty());
        hits.forEach(System.out::println);
    }

    /**
     * 将文本嵌入为向量后直接查询 Elasticsearch，返回按 ES {@code _score} 降序排列的 Top K。
     *
     * @param text 查询文本，不能为空
     * @param topK 返回数量，必须大于 0
     * @return 命中结果，包含 ES 文档 ID、业务 ID、文本、分数和原始 source
     */
    public static List<ElasticsearchHit> search(String text, int topK) throws Exception {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than 0");
        }

        Settings settings = Settings.fromSystemProperties();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        List<Float> vector = embed(client, settings, text);
        return knnSearch(client, settings, vector, topK);
    }

    private static List<Float> embed(HttpClient client, Settings settings, String text) throws Exception {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("model", settings.ollamaModel, "input", text));
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.ollamaUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama embedding request failed: HTTP " + response.statusCode() + ", " + response.body());
        }

        JsonNode embeddings = OBJECT_MAPPER.readTree(response.body()).path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty() || !embeddings.get(0).isArray()) {
            throw new IllegalStateException("Unexpected Ollama /api/embed response: " + response.body());
        }
        List<Float> vector = new ArrayList<>(embeddings.get(0).size());
        for (JsonNode value : embeddings.get(0)) {
            vector.add((float) value.asDouble());
        }
        return vector;
    }

    private static List<ElasticsearchHit> knnSearch(HttpClient client, Settings settings, List<Float> vector, int topK) throws Exception {
        Map<String, Object> knn = Map.of(
                "field", settings.vectorField,
                "query_vector", vector,
                "k", topK,
                "num_candidates", Math.max(100, topK * 10)
        );
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("size", topK, "knn", knn));
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.esUrl + "/" + settings.esIndex + "/_search"))
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth(settings.esUsername, settings.esPassword))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Elasticsearch KNN request failed: HTTP " + response.statusCode() + ", " + response.body());
        }

        List<ElasticsearchHit> hits = new ArrayList<>();
        for (JsonNode hit : OBJECT_MAPPER.readTree(response.body()).path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new ElasticsearchHit(
                    hit.path("_id").asText(),
                    source.path("id").asText(),
                    source.path("result_page_id").asText(),
                    source.path("vector_text").asText(),
                    hit.path("_score").asDouble()));
        }
        return hits;
    }

    private static String basicAuth(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private record Settings(String ollamaUrl, String ollamaModel, String esUrl, String esUsername,
                            String esPassword, String esIndex, String vectorField) {

        private static Settings fromSystemProperties() {
            return new Settings(
                    property("ollama.url", "OLLAMA_URL", OLLAMA_URL),
                    property("ollama.model", "OLLAMA_MODEL", OLLAMA_MODEL),
                    property("es.url", "ES_URL", ES_URL),
                    property("es.username", "ES_USERNAME", ES_USERNAME),
                    property("es.password", "ES_PASSWORD", ES_PASSWORD),
                    property("es.index", "ES_INDEX", ES_INDEX),
                    property("es.vectorField", "ES_VECTOR_FIELD", ES_VECTOR_FIELD)
            );
        }

        private static String property(String systemProperty, String environmentVariable, String defaultValue) {
            String value = System.getProperty(systemProperty);
            return value != null && !value.isBlank() ? value : System.getenv().getOrDefault(environmentVariable, defaultValue);
        }

        private static String requiredProperty(String systemProperty, String environmentVariable) {
            String value = property(systemProperty, environmentVariable, "");
            if (value.isBlank()) {
                throw new IllegalStateException("Set -D" + systemProperty + " or environment variable " + environmentVariable);
            }
            return value;
        }
    }

    public record ElasticsearchHit(String documentId, String id, String resultPageId, String vectorText,
                                   double score) {
    }
}

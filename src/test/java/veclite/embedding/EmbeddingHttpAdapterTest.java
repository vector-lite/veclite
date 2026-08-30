package veclite.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Embedding HTTP 协议适配层单元测试：请求体构造、响应解析与工厂选择的协议正确性。
 */
class EmbeddingHttpAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("工厂选择：http/openai/空 默认 OpenAI 兼容，ollama 与 ollama-embed 各归其位，未知值 fail-fast")
    void factoryShouldSelectAdapterByProvider() {
        assertEquals(OpenAiCompatEmbeddingAdapter.class, EmbeddingHttpAdapter.forProvider("http").getClass());
        assertEquals(OpenAiCompatEmbeddingAdapter.class, EmbeddingHttpAdapter.forProvider(null).getClass());
        assertEquals(OpenAiCompatEmbeddingAdapter.class, EmbeddingHttpAdapter.forProvider("OpenAI").getClass());
        assertEquals(OllamaLegacyEmbeddingAdapter.class, EmbeddingHttpAdapter.forProvider("ollama").getClass());
        assertEquals(OllamaBatchEmbeddingAdapter.class, EmbeddingHttpAdapter.forProvider("ollama-embed").getClass());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EmbeddingHttpAdapter.forProvider("bogus"));
        assertTrue(error.getMessage().contains("Unsupported embedding provider"));
    }

    @Test
    @DisplayName("OpenAI 兼容：单请求携带 model/version/input 数组；解析 data[].embedding 形态")
    void openAiAdapterRequestAndDataShapeResponse() throws Exception {
        EmbeddingHttpAdapter adapter = EmbeddingHttpAdapter.forProvider("http");

        List<byte[]> bodies = adapter.buildRequests("m1", "2", List.of("a", "b"));
        assertEquals(1, bodies.size());
        JsonNode body = MAPPER.readTree(bodies.get(0));
        assertEquals("m1", body.get("model").asText());
        assertEquals("2", body.get("version").asText());
        assertEquals(2, body.get("input").size());

        List<List<Float>> parsed = adapter.parseResponse(MAPPER.readTree("""
                {"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}
                """));
        assertEquals(2, parsed.size());
        assertEquals(0.1f, parsed.get(0).get(0));
        assertEquals(0.4f, parsed.get(1).get(1));
    }

    @Test
    @DisplayName("Ollama 老接口：每条文本一个 {model,prompt} 请求，不含 version/input 字段")
    void ollamaLegacyAdapterBuildsPromptPerText() throws Exception {
        EmbeddingHttpAdapter adapter = EmbeddingHttpAdapter.forProvider("ollama");

        List<byte[]> bodies = adapter.buildRequests("bge-small-zh", "1", List.of("你好", "世界"));
        assertEquals(2, bodies.size());
        JsonNode first = MAPPER.readTree(bodies.get(0));
        assertEquals("bge-small-zh", first.get("model").asText());
        assertEquals("你好", first.get("prompt").asText());
        assertTrue(!first.has("input"), "legacy protocol must not send input array");
        assertTrue(!first.has("version"), "legacy protocol must not send version");
    }

    @Test
    @DisplayName("Ollama 老接口响应：解析 {embedding} 单字段；错误请求返回的空向量被原样保留待上层校验")
    void ollamaLegacyAdapterParsesEmbeddingField() throws Exception {
        EmbeddingHttpAdapter adapter = EmbeddingHttpAdapter.forProvider("ollama");

        List<List<Float>> parsed = adapter.parseResponse(MAPPER.readTree(
                "{\"embedding\":[0.05,0.04,0.08]}"));
        assertEquals(1, parsed.size());
        assertEquals(List.of(0.05f, 0.04f, 0.08f), parsed.get(0));

        // 静默失败陷阱：发送错误字段时 Ollama 返回 {"embedding":[]}，此处保留空向量，由 Provider 统一抛出
        List<List<Float>> empty = adapter.parseResponse(MAPPER.readTree("{\"embedding\":[]}"));
        assertEquals(1, empty.size());
        assertTrue(empty.get(0).isEmpty());
    }

    @Test
    @DisplayName("Ollama 新接口：单请求 {model,input} 原生批量；解析 {embeddings} 二维数组且保持顺序")
    void ollamaBatchAdapterRequestAndResponse() throws Exception {
        EmbeddingHttpAdapter adapter = EmbeddingHttpAdapter.forProvider("ollama-embed");

        List<byte[]> bodies = adapter.buildRequests("bge-small-zh", "1", List.of("a", "b", "c"));
        assertEquals(1, bodies.size());
        JsonNode body = MAPPER.readTree(bodies.get(0));
        assertEquals(3, body.get("input").size());
        assertTrue(!body.has("prompt"));

        List<List<Float>> parsed = adapter.parseResponse(MAPPER.readTree("""
                {"model":"bge-small-zh","embeddings":[[1,2],[3,4],[5,6]]}
                """));
        assertEquals(3, parsed.size());
        assertEquals(5f, parsed.get(2).get(0));
    }

    @Test
    @DisplayName("解析不出向量时返回空列表，由 Provider 抛出明确异常（快速失败而非静默）")
    void unknownShapeShouldYieldEmptyList() throws Exception {
        EmbeddingHttpAdapter legacy = EmbeddingHttpAdapter.forProvider("ollama");
        assertTrue(legacy.parseResponse(MAPPER.readTree("{\"error\":\"boom\"}")).isEmpty());
        EmbeddingHttpAdapter batch = EmbeddingHttpAdapter.forProvider("ollama-embed");
        assertTrue(batch.parseResponse(MAPPER.readTree("{\"unexpected\":1}")).isEmpty());
    }
}

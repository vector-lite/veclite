package veclite.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Tag;
import veclite.config.VectorLiteProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联调测试：调用真实的阿里云百炼（DashScope OpenAI 兼容模式）Embedding 服务，
 * 验证数据源配置中的 api-key / dimension 能正确生效。
 *
 * <p>使用方法：
 * <pre>
 *   PowerShell: $env:DASHSCOPE_API_KEY="sk-你的key"
 *   运行: ./gradlew manualTest --tests "veclite.embedding.HttpEmbeddingProviderTest"
 * </pre>
 * 没设置环境变量时，测试自动跳过（@EnabledIfEnvironmentVariable）。
 */
@Tag("manual")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class HttpEmbeddingProviderTest {

    private static final String MODEL_NAME = "text-embedding-v3";
    private static final String MODEL_VERSION = "v3";
    private static final String ENDPOINT =
            "https://llm-dij67chcqa06gb7k.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings";

    /** 构造一个仅内存生效的注册中心（store=null，不落数据库），并注册带 api-key 的数据源 */
    private EmbeddingModelRegistry newRegistry(String apiKey) {
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName(MODEL_NAME);
        config.setVersion(MODEL_VERSION);
        // DashScope 兼容模式走 OpenAI 协议
        config.setProvider("openai");
        config.setUrl(ENDPOINT);
        config.setApiKey(apiKey);
        config.setTimeoutMillis(30000);
        config.setBatchSize(8);
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(null);
        registry.save(config);
        return registry;
    }

    @Test
    void testDashScopeSingleEmbedding() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "环境变量 DASHSCOPE_API_KEY 未设置");

        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(newRegistry(apiKey));
        List<Float> vector = provider.embed(MODEL_NAME, MODEL_VERSION, "你好世界");

        assertNotNull(vector, "embedding 返回为空");
        assertFalse(vector.isEmpty(), "embedding 维度为 0");
        // text-embedding-v3 默认维度 1024
        assertTrue(vector.size() >= 64 && vector.size() <= 2048,
                "embedding 维度异常: " + vector.size());
    }

    @Test
    void testDashScopeBatchEmbedding() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey);

        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(newRegistry(apiKey));

        List<String> texts = List.of("你好", "世界", "向量数据库");
        List<List<Float>> vectors = provider.embedBatch(MODEL_NAME, MODEL_VERSION, texts);

        assertNotNull(vectors);
        assertEquals(3, vectors.size(), "批量返回数量不对");
        for (int i = 0; i < vectors.size(); i++) {
            assertEquals(vectors.get(0).size(), vectors.get(i).size(),
                    "第 " + i + " 条向量维度不一致");
        }
    }

    @Test
    void testDashScopeDimensionParameter() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey);

        EmbeddingModelRegistry registry = newRegistry(apiKey);
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(registry);

        // 请求 512 维：OpenAI 兼容协议的 dimensions 降维参数
        List<Float> vector = provider.embed(MODEL_NAME, MODEL_VERSION, "维度测试", 512);

        assertNotNull(vector);
        assertEquals(512, vector.size(), "请求 512 维但服务端返回了 " + vector.size() + " 维");
    }
}

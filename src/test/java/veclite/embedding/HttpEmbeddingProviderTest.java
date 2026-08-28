package veclite.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import veclite.config.VectorLiteProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：调用真实的阿里云百炼 Embedding 服务。
 *
 * 使用方法：
 *   1. 在 PowerShell 设置环境变量：
 *      $env:DASHSCOPE_API_KEY="sk-你的key"
 *   2. 运行测试：
 *      ./gradlew test --tests "veclite.embedding.HttpEmbeddingProviderTest"
 *
 * 没设置环境变量时，测试自动跳过（@EnabledIfEnvironmentVariable）。
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class HttpEmbeddingProviderTest {

    @Test
    void testDashScopeSingleEmbedding() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "环境变量 DASHSCOPE_API_KEY 未设置");

        // 1. 构造配置
        VectorLiteProperties properties = new VectorLiteProperties();
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName("text-embedding-v3");
        config.setVersion("v3");
        config.setProvider("dashscope");
        // 阿里云百炼 OpenAI 兼容地址
        config.setUrl("https://llm-dij67chcqa06gb7k.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings");
        config.setApiKey(apiKey);
        config.setTimeoutMillis(30000);
        config.setBatchSize(8);

        Map<String, VectorLiteProperties.ModelConfig> models = new HashMap<>();
        models.put("text-embedding-v3", config);
        properties.getEmbedding().setModels(models);
        properties.getEmbedding().setDefaultModel("text-embedding-v3");

        // 2. 调用 Embedding
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(properties);
        List<Float> vector = provider.embed("text-embedding-v3", "v3", "你好世界");

        // 3. 验证
        assertNotNull(vector, "embedding 返回为空");
        assertFalse(vector.isEmpty(), "embedding 维度为 0");
        System.out.println("✓ Embedding 成功，维度 = " + vector.size());
        System.out.println("  前 5 个值: " + vector.subList(0, Math.min(5, vector.size())));

        // text-embedding-v3 默认维度 1024
        assertTrue(vector.size() >= 64 && vector.size() <= 2048,
                "embedding 维度异常: " + vector.size());
    }

    @Test
    void testDashScopeBatchEmbedding() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey);

        VectorLiteProperties properties = new VectorLiteProperties();
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName("text-embedding-v3");
        config.setVersion("v3");
        config.setProvider("dashscope");
        config.setUrl("https://llm-dij67chcqa06gb7k.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings");
        config.setApiKey(apiKey);
        config.setTimeoutMillis(30000);
        config.setBatchSize(8);

        Map<String, VectorLiteProperties.ModelConfig> models = new HashMap<>();
        models.put("text-embedding-v3", config);
        properties.getEmbedding().setModels(models);
        properties.getEmbedding().setDefaultModel("text-embedding-v3");

        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(properties);

        // 批量 3 条
        List<String> texts = List.of("你好", "世界", "向量数据库");
        List<List<Float>> vectors = provider.embedBatch("text-embedding-v3", "v3", texts);

        assertNotNull(vectors);
        assertEquals(3, vectors.size(), "批量返回数量不对");
        for (int i = 0; i < vectors.size(); i++) {
            assertEquals(vectors.get(0).size(), vectors.get(i).size(),
                    "第 " + i + " 条向量维度不一致");
        }
        System.out.println("✓ 批量 embedding 成功，3 条向量，维度 = " + vectors.get(0).size());
    }
}

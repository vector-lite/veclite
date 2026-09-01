package veclite.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.api.EmbeddingProvider;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingModelRegistry;
import veclite.embedding.EmbeddingService;
import veclite.model.EmbedTextRequest;
import veclite.model.EmbedVectorResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code POST /embedding/models/{name}/embed} 单文本向量化端点的回归测试。
 * 只验证端点层的参数校验与结果组装，Provider 使用固定输出桩，不发起外部 HTTP 调用。
 */
class EmbedTextEndpointTest {

    /** 固定维度输出的桩 Provider，模拟真实 Embedding 服务 */
    private static final class StubProvider implements EmbeddingProvider {
        @Override
        public List<Float> embed(String modelName, String modelVersion, String text, int dimension) {
            int dim = dimension > 0 ? dimension : 4;
            List<Float> vector = new java.util.ArrayList<>(dim);
            for (int i = 0; i < dim; i++) {
                vector.add((float) (text.length() + i));
            }
            return vector;
        }

        @Override
        public List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts, int dimension) {
            return texts.stream().map(t -> embed(modelName, modelVersion, t, dimension)).toList();
        }
    }

    private VectorLiteDebugController newController(EmbeddingService service) {
        // 端点逻辑只依赖 EmbeddingService，其余协作对象在本测试中不触达，可置 null
        return new VectorLiteDebugController(null, null, service, null, null);
    }

    private EmbeddingService serviceWithRegisteredModel() {
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(null);
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName("stub-model");
        config.setVersion("1");
        config.setProvider("http");
        config.setUrl("http://localhost:0/embed");
        registry.save(config);
        return new EmbeddingService(new StubProvider(), registry);
    }

    @Test
    @DisplayName("输入文本返回向量，dimension 为向量实际长度，version 回退到主版本")
    void embedTextReturnsVectorWithActualDimension() {
        VectorLiteDebugController controller = newController(serviceWithRegisteredModel());

        EmbedTextRequest request = new EmbedTextRequest();
        request.setText("你好向量");
        EmbedVectorResult result = controller.embedText("stub-model", null, request);

        assertNotNull(result);
        assertEquals("stub-model", result.getName());
        assertEquals("1", result.getVersion());
        assertEquals(4, result.getDimension());
        assertEquals(4, result.getVector().size());
        assertEquals(7.0f, result.getVector().get(3), 1e-6);
    }

    @Test
    @DisplayName("显式指定 dimension 时按请求维度返回")
    void embedTextHonorsRequestedDimension() {
        VectorLiteDebugController controller = newController(serviceWithRegisteredModel());

        EmbedTextRequest request = new EmbedTextRequest();
        request.setText("abc");
        request.setDimension(7);
        EmbedVectorResult result = controller.embedText("stub-model", "1", request);

        assertEquals(7, result.getDimension());
        assertEquals("1", result.getVersion());
    }

    @Test
    @DisplayName("空白文本抛出 IllegalArgumentException（由全局异常处理映射为 400）")
    void embedTextRejectsBlankText() {
        VectorLiteDebugController controller = newController(serviceWithRegisteredModel());

        EmbedTextRequest blank = new EmbedTextRequest();
        blank.setText("   ");
        assertThrows(IllegalArgumentException.class,
                () -> controller.embedText("stub-model", null, blank));

        assertThrows(IllegalArgumentException.class,
                () -> controller.embedText("stub-model", null, null));
    }
}

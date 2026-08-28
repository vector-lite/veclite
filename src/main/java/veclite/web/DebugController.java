package veclite.web;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调试用 Controller：直接暴露 Embedding / Engine 状态用于排错。
 * 仅在 veclite.web.enabled=true 时启用。
 */
@RestController
@RequestMapping("${veclite.web.base-path:/veclite/api/v1}/_debug")
@CrossOrigin(origins = "${veclite.web.allowed-origins:*}")
@ConditionalOnProperty(name = "veclite.web.enabled", havingValue = "true")
public class DebugController {

    @Autowired(required = false)
    private EmbeddingProvider embeddingProvider;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    @Autowired(required = false)
    private VectorEngineClient engineClient;

    @Autowired
    private VectorLiteProperties properties;

    /**
     * 健康检查：返回 Spring Bean 注入状态
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("embeddingProvider", embeddingProvider != null
                ? embeddingProvider.getClass().getSimpleName() : "NULL");
        result.put("embeddingService", embeddingService != null
                ? embeddingService.getClass().getSimpleName() : "NULL");
        result.put("engineClient", engineClient != null
                ? engineClient.getClass().getSimpleName() : "NULL");

        // 配置信息
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("defaultModel", properties.getEmbedding().getDefaultModel());
        cfg.put("models", properties.getEmbedding().getModels().keySet());
        result.put("embeddingConfig", cfg);

        // 环境变量
        Map<String, Object> env = new HashMap<>();
        env.put("DASHSCOPE_API_KEY_set", System.getenv("DASHSCOPE_API_KEY") != null);
        String key = System.getenv("DASHSCOPE_API_KEY");
        if (key != null) {
            env.put("DASHSCOPE_API_KEY_preview", key.substring(0, Math.min(8, key.length())) + "...");
        }
        result.put("env", env);

        return result;
    }

    /**
     * 直接测试 Embedding 调用，绕开 store 流程
     */
    @GetMapping("/embed-test")
    public Map<String, Object> embedTest(@RequestParam(defaultValue = "你好世界") String text) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (embeddingProvider == null) {
                result.put("status", "FAIL");
                result.put("error", "EmbeddingProvider is NULL - 没有注入");
                return result;
            }
            String modelName = properties.getEmbedding().getDefaultModel();
            if (modelName == null) {
                result.put("status", "FAIL");
                result.put("error", "没有配置 default-model");
                return result;
            }

            List<Float> vector = embeddingProvider.embed(modelName, null, text);
            result.put("status", "OK");
            result.put("model", modelName);
            result.put("text", text);
            result.put("dimension", vector.size());
            result.put("preview", vector.subList(0, Math.min(10, vector.size())));
            return result;
        } catch (Exception e) {
            result.put("status", "FAIL");
            result.put("error", e.getClass().getName() + ": " + e.getMessage());
            result.put("stackTrace", getStackTrace(e));
            return result;
        }
    }

    /**
     * 测试 Embedding 服务的 embedTexts
     */
    @GetMapping("/embed-service-test")
    public Map<String, Object> embedServiceTest(@RequestParam(defaultValue = "你好世界") String text) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (embeddingService == null) {
                result.put("status", "FAIL");
                result.put("error", "EmbeddingService is NULL");
                return result;
            }
            String modelName = properties.getEmbedding().getDefaultModel();
            List<List<Float>> vectors = embeddingService.embedTexts(modelName, null, List.of(text));
            result.put("status", "OK");
            result.put("model", modelName);
            result.put("text", text);
            result.put("dimension", vectors.get(0).size());
            result.put("preview", vectors.get(0).subList(0, Math.min(10, vectors.get(0).size())));
            return result;
        } catch (Exception e) {
            result.put("status", "FAIL");
            result.put("error", e.getClass().getName() + ": " + e.getMessage());
            result.put("stackTrace", getStackTrace(e));
            return result;
        }
    }

    private String getStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("  at ").append(el).append("\n");
            if (sb.length() > 3000) {
                sb.append("  ... (truncated)\n");
                break;
            }
        }
        return sb.toString();
    }
}

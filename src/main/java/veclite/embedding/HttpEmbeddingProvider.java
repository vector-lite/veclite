package veclite.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import veclite.api.EmbeddingProvider;
import veclite.config.VectorLiteProperties;

import java.io.OutputStream;
import java.util.Objects;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通过 HTTP 调用远程 Embedding 服务的适配器。
 * <p>
 * 本类只承担传输职责（连接、超时、状态码），请求体构造与响应解析由
 * {@link EmbeddingHttpAdapter} 协议适配层完成，协议选择由模型配置的
 * {@code provider} 字段驱动（http/openai、ollama、ollama-embed）。
 * 对"服务端静默失败"的响应（如 Ollama 老接口返回空向量）显式校验并抛出，
 * 避免零维向量流入 Store 造成静默数据损坏。
 */
public class HttpEmbeddingProvider implements EmbeddingProvider {

    private static final String HTTP_METHOD = "POST";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final int HTTP_OK = 200;

    /** 模型配置注册中心（数据库维护），模型配置只从这里解析 */
    private final EmbeddingModelRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpEmbeddingProvider(EmbeddingModelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public List<Float> embed(String modelName, String modelVersion, String text, int dimension) {
        List<List<Float>> results = embedBatch(modelName, modelVersion, Collections.singletonList(text), dimension);
        if (results != null && !results.isEmpty()) {
            return results.get(0);
        }
        throw new IllegalStateException("Empty embedding response for model: " + modelName);
    }

    /** {@inheritDoc} */
    @Override
    public List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts, int dimension) {
        VectorLiteProperties.ModelConfig modelConfig = resolveModelConfig(modelName, modelVersion);
        String urlString = modelConfig.getUrl();
        if (urlString == null || urlString.isEmpty()) {
            throw new IllegalArgumentException("No URL configured for embedding model: " + modelName);
        }

        EmbeddingHttpAdapter adapter = EmbeddingHttpAdapter.forProvider(modelConfig.getProvider());
        String effectiveVersion = modelVersion != null ? modelVersion : modelConfig.getVersion();
        // 维度优先级：调用方传入 > 数据源自带配置；两者都未指定时由服务端决定
        int effectiveDimension = dimension > 0 ? dimension : modelConfig.getDimension();
        List<byte[]> requestBodies = adapter.buildRequests(modelName, effectiveVersion, texts, effectiveDimension);

        try {
            URL url = new URL(urlString);
            List<List<Float>> embeddings = new ArrayList<>(texts.size());
            for (byte[] requestBody : requestBodies) {
                HttpURLConnection conn = openConnection(url, modelConfig.getTimeoutMillis(), modelConfig.getApiKey());
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody);
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != HTTP_OK) {
                    throw new RuntimeException("HTTP embedding request failed with status code: " + responseCode);
                }

                JsonNode root = objectMapper.readTree(conn.getInputStream());
                appendParsed(adapter.parseResponse(root), embeddings);
            }
            validateBatchResult(modelName, texts, embeddings);
            return embeddings;
        } catch (Exception e) {
            if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("HTTP Embedding request failed for model [" + modelName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 打开 HTTP 连接：统一设置方法、Content-Type、双向超时与可选的 Bearer 鉴权。
     *
     * @param apiKey 数据源配置的 API Key；为 null 或空时不发送 Authorization 头
     */
    private HttpURLConnection openConnection(URL url, int timeoutMillis, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(HTTP_METHOD);
        conn.setRequestProperty("Content-Type", JSON_CONTENT_TYPE);
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setConnectTimeout(timeoutMillis);
        conn.setReadTimeout(timeoutMillis);
        conn.setDoOutput(true);
        return conn;
    }

    /** 空向量与解析失败防护：防止零维向量静默流入 Store */
    private void appendParsed(List<List<Float>> parsed, List<List<Float>> sink) {
        if (parsed == null || parsed.isEmpty()) {
            throw new IllegalStateException(
                    "Failed to parse vector embedding from HTTP response. "
                            + "Check that the model URL matches the configured provider protocol.");
        }
        for (List<Float> vector : parsed) {
            if (vector.isEmpty()) {
                throw new IllegalStateException(
                        "Embedding service returned an empty vector. "
                                + "This usually means the request body format does not match the provider protocol "
                                + "(e.g. Ollama /api/embeddings requires 'prompt', not 'input').");
            }
            sink.add(vector);
        }
    }

    /** 批量结果必须与输入文本一一对应，保证上层 autoEmbed 的对位赋值安全 */
    private void validateBatchResult(String modelName, List<String> texts, List<List<Float>> embeddings) {
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding response count mismatch for model [" + modelName + "]. "
                            + "Expected: " + texts.size() + ", actual: " + embeddings.size());
        }
    }

    /**
     * 解析模型配置：按（名称， 版本）查找；版本为空时回退该名称的主版本配置。
     * 请求模型未命中时沿用默认模型回退规则。
     *
     * @param modelName 请求使用的模型名
     * @return 可用于发起 HTTP 请求的模型配置
     * @throws IllegalArgumentException 未找到请求模型及默认模型配置时抛出
     */
    private VectorLiteProperties.ModelConfig resolveModelConfig(String modelName, String modelVersion) {
        VectorLiteProperties.ModelConfig config = registry.find(modelName, modelVersion);
        if (config == null) {
            EmbeddingModelRef defaultRef = registry.defaultRef();
            if (defaultRef != null && !defaultRef.name().equals(modelName)) {
                config = registry.find(defaultRef.name(), defaultRef.version());
            }
        }
        if (config != null) {
            return config;
        }
        throw new IllegalArgumentException(
                "No configuration found for embedding model [" + modelName + "]. "
                        + "Please add it via the data source management API or page first.");
    }
}

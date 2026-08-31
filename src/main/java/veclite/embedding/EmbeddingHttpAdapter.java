package veclite.embedding;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Embedding HTTP 协议适配层。
 * <p>
 * 不同向量服务的请求体与响应体格式差异较大（如 OpenAI 兼容服务的 {@code data[]} 数组、
 * Ollama 老接口 {@code /api/embeddings} 的 {@code prompt} 单条请求与 {@code embedding} 响应、
 * Ollama 新接口 {@code /api/embed} 的 {@code input} 批量请求与 {@code embeddings} 响应），
 * 本接口将"协议格式"从"HTTP 传输"中隔离：{@link HttpEmbeddingProvider} 只负责连接、
 * 超时与状态码处理，请求体构造与响应解析全部委托给适配器实现。
 * <p>
 * 适配器选择由模型配置的 {@code provider} 字段驱动（见 {@link #forProvider}），
 * 新增数据源时实现本接口并在工厂注册即可。
 */
public interface EmbeddingHttpAdapter {

    /**
     * 构造本次批量调用所需的全部 HTTP 请求体（有序）。
     * 单请求协议返回 1 个元素；仅支持单条文本的协议按输入顺序返回 N 个元素，
     * 调用方按序执行并顺序拼接各响应的解析结果，还原批量语义。
     *
     * @param modelName    请求的模型名（即配置键，直接进入请求体 model 字段）
     * @param modelVersion 模型版本（不参与请求体的协议可忽略）
     * @param texts        本批次的文本列表，非空
     * @param dimension    调用方期望的目标维度；大于 0 且协议支持时写入请求体，否则忽略
     */
    List<byte[]> buildRequests(String modelName, String modelVersion, List<String> texts, int dimension);

    /**
     * 兼容旧调用：不指定目标维度。
     */
    default List<byte[]> buildRequests(String modelName, String modelVersion, List<String> texts) {
        return buildRequests(modelName, modelVersion, texts, 0);
    }

    /**
     * 解析单个响应体。返回的向量数与本适配器对应单次请求覆盖的文本数一致，
     * 向量维度由服务端决定；返回空向量（维度 0）表示服务端静默失败，
     * 由调用方统一校验并抛出明确异常。
     */
    List<List<Float>> parseResponse(JsonNode root);

    /** 协议标识，与 ModelConfig.provider 配置值一致 */
    String protocol();

    /**
     * 按 provider 配置选择适配器；未配置或配置为 http/openai 时使用 OpenAI 兼容适配器，
     * 保持既有配置文件行为不变；未知协议 fail-fast 报错，避免请求格式静默不匹配。
     */
    static EmbeddingHttpAdapter forProvider(String provider) {
        String normalized = provider == null ? "openai" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "", "http", "openai" -> OpenAiCompatEmbeddingAdapter.INSTANCE;
            case "ollama" -> OllamaLegacyEmbeddingAdapter.INSTANCE;
            case "ollama-embed" -> OllamaBatchEmbeddingAdapter.INSTANCE;
            default -> throw new IllegalArgumentException(
                    "Unsupported embedding provider [" + provider + "]. Supported: http/openai, ollama, ollama-embed");
        };
    }
}

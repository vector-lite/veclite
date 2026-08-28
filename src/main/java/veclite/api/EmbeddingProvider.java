package veclite.api;

import java.util.List;

public interface EmbeddingProvider {

    /**
     * @param dimension 调用方期望的目标向量维度。<b>大于 0</b> 时实现应优先按此维度调用 Embedding 服务；
     *                 <b>0 或负数</b> 时由实现自行决定（多数情况=走 yml 配置或服务默认值）。
     */
    List<Float> embed(String modelName, String modelVersion, String text, int dimension);

    /**
     * 批量版本，{@code dimension} 语义同 {@link #embed}。
     */
    List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts, int dimension);

    /**
     * 兼容旧调用：未指定 dimension。
     */
    default List<Float> embed(String modelName, String modelVersion, String text) {
        return embed(modelName, modelVersion, text, 0);
    }

    /**
     * 兼容旧调用：未指定 dimension。
     */
    default List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts) {
        return embedBatch(modelName, modelVersion, texts, 0);
    }
}

package veclite.math;

/**
 * 向量相似度/距离计算器接口。
 */
public interface VectorMath {

    /**
     * 根据指定的 Metric 算法计算向量 a 与向量 b 的得分。
     *
     * @param metric 距离度量算法 (COSINE, DOT_PRODUCT/IP, EUCLIDEAN/L2)
     * @param a      查询向量
     * @param b      候选向量
     * @return 相似度得分或距离
     */
    float calculate(String metric, float[] a, float[] b);

    /**
     * 【零拷贝重载】根据指定的 Metric 算法，直接在平铺数组 bData 的指定偏移量范围上计算相似度。
     *
     * @param metric  距离度量算法
     * @param a       查询向量
     * @param bData   平铺存储所有向量的一维数组
     * @param bOffset 候选向量在 bData 中的起始偏移量索引
     * @param dim     向量维度
     * @return 相似度得分或距离
     */
    float calculate(String metric, float[] a, float[] bData, int bOffset, int dim);

    /**
     * 计算余弦相似度。
     */
    float cosineSimilarity(float[] a, float[] b);

    /**
     * 【零拷贝重载】从平铺数组指定偏移量计算余弦相似度。
     */
    float cosineSimilarity(float[] a, float[] bData, int bOffset, int dim);

    /**
     * 计算向量点积。
     */
    float dotProduct(float[] a, float[] b);

    /**
     * 【零拷贝重载】从平铺数组指定偏移量计算向量点积。
     */
    float dotProduct(float[] a, float[] bData, int bOffset, int dim);

    /**
     * 计算欧氏距离。
     */
    float euclideanDistance(float[] a, float[] b);

    /**
     * 【零拷贝重载】从平铺数组指定偏移量计算欧氏距离。
     */
    float euclideanDistance(float[] a, float[] bData, int bOffset, int dim);
}


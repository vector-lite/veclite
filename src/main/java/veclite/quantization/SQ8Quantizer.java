package veclite.quantization;

/**
 * SQ8 (Scalar Quantization 8-bit) 标量量化编解码与相似度算法计算器。
 * <p>
 * 采用 <b>逐维度 (Per-Dimension)</b> 量化参数：每个维度独立维护 {min, scale}，
 * 其中 scale = (max - min) / 255。量化参数在数据校准阶段统计完成后即<b>冻结不变</b>，
 * 后续写入的向量若超出参数范围将被 clamp 到边界，保证已存向量的编解码永远一致，
 * 从根源上消除旧版"全局 min/max 随写入漂移导致历史数据解码错位"的精度劣化问题。
 * @author zhaoyuanlu
 */
public class SQ8Quantizer {

    /**
     * 将 32-bit float 向量按逐维度参数量化为 8-bit byte 向量。
     * 超出校准范围的值会被 clamp 到 [-128, 127] 边界。
     * @param src        float 向量数组
     * @param minPerDim  每个维度的最小值（冻结后的校准参数）
     * @param scalePerDim 每个维度的量化步长 = (max - min) / 255
     * @param dest       目标 byte 数组
     */
    public static void quantize(float[] src, float[] minPerDim, float[] scalePerDim, byte[] dest) {
        for (int i = 0; i < src.length; i++) {
            int quant = Math.round((src[i] - minPerDim[i]) / scalePerDim[i] - 128.0f);
            if (quant < -128) quant = -128;
            else if (quant > 127) quant = 127;
            dest[i] = (byte) quant;
        }
    }

    /**
     * 将 8-bit byte 向量按逐维度参数反量化还原为 32-bit float 数组。
     */
    public static void dequantize(byte[] src, float[] minPerDim, float[] scalePerDim, float[] dest) {
        for (int i = 0; i < src.length; i++) {
            dest[i] = minPerDim[i] + scalePerDim[i] * (src[i] + 128.0f);
        }
    }

    /**
     * 计算 float 向量的 L2 范数 ‖v‖（upsert 时预计算并缓存，搜索时免重复计算）。
     */
    public static float l2Norm(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return (float) Math.sqrt(sum);
    }

    /**
     * 【零拷贝】基于预计算范数的余弦相似度。
     * 直接在平铺 byte 数组的 targetOffset 位置读取目标向量，无需拷贝；
     * 目标向量范数由调用方传入缓存值，查询向量范数倒数由调用方整次搜索计算一次。
     * <p>
     * 数学展开：dot(q, t) = Σ q_d · (min_d + scale_d · (b_d + 128))
     */
    public static float calculateCosineWithNorms(float[] query, byte[] targetData, int targetOffset,
                                                 float[] minPerDim, float[] scalePerDim,
                                                 float queryNormInv, float targetNormInv) {
        float dot = 0.0f;
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * (minPerDim[i] + scalePerDim[i] * (targetData[targetOffset + i] + 128.0f));
        }
        return dot * queryNormInv * targetNormInv;
    }

    /**
     * 【零拷贝】基于预计算范数的欧氏距离 (L2)。
     * 利用恒等式 ‖q - t‖² = ‖q‖² + ‖t‖² - 2·q·t，避免逐维度维护差值平方和之外的额外开销。
     */
    public static float calculateEuclideanWithNorms(float[] query, byte[] targetData, int targetOffset,
                                                    float[] minPerDim, float[] scalePerDim,
                                                    float queryNormSq, float targetNormSq) {
        float dot = 0.0f;
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * (minPerDim[i] + scalePerDim[i] * (targetData[targetOffset + i] + 128.0f));
        }
        float sq = queryNormSq + targetNormSq - 2.0f * dot;
        return sq > 0.0f ? (float) Math.sqrt(sq) : 0.0f;
    }

    /**
     * 【零拷贝】点积 / 内积 (Dot Product)。不做范数归一化，保留模长信息。
     */
    public static float calculateDotProduct(float[] query, byte[] targetData, int targetOffset,
                                            float[] minPerDim, float[] scalePerDim) {
        float dot = 0.0f;
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * (minPerDim[i] + scalePerDim[i] * (targetData[targetOffset + i] + 128.0f));
        }
        return dot;
    }
}

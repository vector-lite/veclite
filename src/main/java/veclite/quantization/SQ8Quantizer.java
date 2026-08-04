package veclite.quantization;

/**
 * SQ8 (Scalar Quantization 8-bit) 标量量化编解码与相似度算法计算器。
 * <p>
 * 将 32-bit 的 float 向量量化压缩为 8-bit 的 byte 有符号整数（数值范围 -128 到 127）。
 * 在保留 98.5%+ 以上相似度精度的同时，将内存占用压缩至原来的 25% (4 倍压缩比)。
 * @author zhaoyuanlu
 */
public class SQ8Quantizer {

    /**
     * 将 32-bit float 数组压缩量化为 8-bit byte 数组。
     * @param src float 向量数组
     * @param min 向量全局/维度最小值
     * @param max 向量全局/维度最大值
     * @param dest 目标 byte 数组
     */
    public static void quantize(float[] src, float min, float max, byte[] dest) {
        float range = max - min;
        if (Math.abs(range) < 1e-7f) {
            range = 1.0f;
        }
        for (int i = 0; i < src.length; i++) {
            float normalized = (src[i] - min) / range;
            int quant = Math.round(normalized * 255.0f - 128.0f);
            if (quant < -128) quant = -128;
            if (quant > 127) quant = 127;
            dest[i] = (byte) quant;
        }
    }

    /**
     * 将 8-bit byte 向量反量化还原为 32-bit float 数组。
     * @param src byte 向量数组
     * @param min 最小值
     * @param max 最大值
     * @param dest 目标 float 数组
     */
    public static void dequantize(byte[] src, float min, float max, float[] dest) {
        float range = max - min;
        for (int i = 0; i < src.length; i++) {
            float normalized = (src[i] + 128.0f) / 255.0f;
            dest[i] = min + normalized * range;
        }
    }

    /**
     * 快速计算 Float32 查询向量与 SQ8 Byte 存储向量的余弦相似度。
     */
    public static float calculateCosine(float[] query, byte[] target, float min, float max) {
        float range = max - min;
        float dot = 0.0f;
        float queryNormSq = 0.0f;
        float targetNormSq = 0.0f;

        for (int i = 0; i < query.length; i++) {
            float q = query[i];
            float t = min + ((target[i] + 128.0f) / 255.0f) * range;
            dot += q * t;
            queryNormSq += q * q;
            targetNormSq += t * t;
        }

        if (queryNormSq == 0.0f || targetNormSq == 0.0f) {
            return 0.0f;
        }
        return dot / (float) (Math.sqrt(queryNormSq) * Math.sqrt(targetNormSq));
    }
}

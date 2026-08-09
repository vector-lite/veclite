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
     * SQ8 查询级预计算上下文（保存查询入口处单次预计算的常量项）。
     */
    public static class SQ8QueryPrecomputation {
        public final float[] query;
        public final int dimension;
        public final float querySum;
        public final float queryNormSq;
        public final float queryNorm;
        public final float c1;
        public final float c2;
        public final float c1_querySum;
        public final float d_c1_sq;
        public final float c1_c2_2;
        public final float c2_sq;

        public SQ8QueryPrecomputation(float[] query, float min, float max) {
            this.query = query;
            this.dimension = query.length;
            float range = max - min;
            if (Math.abs(range) < 1e-7f) {
                range = 1.0f;
            }
            this.c2 = range / 255.0f;
            this.c1 = min + 128.0f * this.c2;

            float qSum = 0.0f;
            float qNormSq = 0.0f;
            for (float q : query) {
                qSum += q;
                qNormSq += q * q;
            }
            this.querySum = qSum;
            this.queryNormSq = qNormSq;
            this.queryNorm = (float) Math.sqrt(qNormSq);
            this.c1_querySum = c1 * querySum;
            this.d_c1_sq = dimension * c1 * c1;
            this.c1_c2_2 = 2.0f * c1 * c2;
            this.c2_sq = c2 * c2;
        }
    }

    /**
     * 在查询入口处执行单次 O(d) 预计算。
     */
    public static SQ8QueryPrecomputation precompute(float[] query, float min, float max) {
        return new SQ8QueryPrecomputation(query, min, max);
    }

    /**
     * 免反量化预计算打分（针对堆内 byte 数组，自动计算目标向量模长）。
     */
    public static float calculateScorePrecomputed(SQ8QueryPrecomputation precomp, byte[] target, int offset, String metric) {
        int dim = precomp.dimension;
        int byteSum = 0;
        int byteSqSum = 0;
        for (int i = 0; i < dim; i++) {
            int b = target[offset + i];
            byteSum += b;
            byteSqSum += b * b;
        }
        float targetNormSq = precomp.d_c1_sq + precomp.c1_c2_2 * byteSum + precomp.c2_sq * byteSqSum;
        return calculateScorePrecomputed(precomp, target, offset, targetNormSq, metric);
    }

    /**
     * 免反量化预计算打分（针对堆内 byte 数组，单循环极速 SIMD 向量化）。
     * @param targetNormSq 目标向量的平方模长（提前在 Upsert 时计算好，避免在检索循环中二次遍历）
     */
    public static float calculateScorePrecomputed(SQ8QueryPrecomputation precomp, byte[] target, int offset, float targetNormSq, String metric) {
        if (precomp.queryNormSq == 0.0f) return 0.0f;

        float[] query = precomp.query;
        int dim = precomp.dimension;
        float rawDot = 0.0f;

        // 核心单循环：纯 float * byte 乘加，JIT C2 极速 AVX2/NEON SIMD 向量化
        for (int i = 0; i < dim; i++) {
            rawDot += query[i] * target[offset + i];
        }

        float dot = precomp.c1_querySum + precomp.c2 * rawDot;

        if ("EUCLIDEAN".equalsIgnoreCase(metric) || "L2".equalsIgnoreCase(metric)) {
            float distSq = precomp.queryNormSq + targetNormSq - 2.0f * dot;
            return (float) Math.sqrt(Math.max(0.0f, distSq));
        } else if ("DOT_PRODUCT".equalsIgnoreCase(metric) || "INNER_PRODUCT".equalsIgnoreCase(metric)) {
            return dot;
        } else { // COSINE
            if (targetNormSq <= 0.0f) return 0.0f;
            return dot / (precomp.queryNorm * (float) Math.sqrt(targetNormSq));
        }
    }

    /**
     * 免反量化预计算打分（针对 DirectByteBuffer 绝对寻址，零对象分配）。
     */
    public static float calculateScorePrecomputed(SQ8QueryPrecomputation precomp, java.nio.ByteBuffer directBuffer, int byteOffset, String metric) {
        if (precomp.queryNormSq == 0.0f) return 0.0f;

        float[] query = precomp.query;
        int dim = precomp.dimension;
        float rawDot = 0.0f;
        int byteSum = 0;
        int byteSqSum = 0;

        for (int i = 0; i < dim; i++) {
            int b = directBuffer.get(byteOffset + i);
            rawDot += query[i] * b;
            byteSum += b;
            byteSqSum += b * b;
        }

        float dot = precomp.c1_querySum + precomp.c2 * rawDot;

        if ("EUCLIDEAN".equalsIgnoreCase(metric) || "L2".equalsIgnoreCase(metric)) {
            float targetNormSq = precomp.d_c1_sq + precomp.c1_c2_2 * byteSum + precomp.c2_sq * byteSqSum;
            float distSq = precomp.queryNormSq + targetNormSq - 2.0f * dot;
            return (float) Math.sqrt(Math.max(0.0f, distSq));
        } else if ("DOT_PRODUCT".equalsIgnoreCase(metric) || "INNER_PRODUCT".equalsIgnoreCase(metric)) {
            return dot;
        } else { // COSINE
            float targetNormSq = precomp.d_c1_sq + precomp.c1_c2_2 * byteSum + precomp.c2_sq * byteSqSum;
            if (targetNormSq <= 0.0f) return 0.0f;
            return dot / (precomp.queryNorm * (float) Math.sqrt(targetNormSq));
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

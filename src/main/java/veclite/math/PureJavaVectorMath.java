package veclite.math;

/**
 * 纯 Java 实现的向量相似度/距离计算器。
 * <p>
 * 包含了：
 * 1. 余弦相似度 (Cosine Similarity)
 * 2. 向量点积 (Dot Product / Inner Product)
 * 3. 欧氏距离 (Euclidean Distance / L2)
 * <p>
 * 支持指定 offset 偏移量在平铺数组上进行【零拷贝计算】，避免在内存遍历时频繁拷贝数组。
 * 内部利用了 Loop Unrolling (4路循环展开) 技术加速 CPU 计算性能。
 */
public class PureJavaVectorMath implements VectorMath {

    /**
     * 根据指定的 Metric 算法计算向量 a 与 b 的得分。
     */
    @Override
    public float calculate(String metric, float[] a, float[] b) {
        if (metric == null || "COSINE".equalsIgnoreCase(metric)) {
            return cosineSimilarity(a, b);
        } else if ("DOT_PRODUCT".equalsIgnoreCase(metric) || "IP".equalsIgnoreCase(metric)) {
            return dotProduct(a, b);
        } else if ("EUCLIDEAN".equalsIgnoreCase(metric) || "L2".equalsIgnoreCase(metric)) {
            return euclideanDistance(a, b);
        }
        return cosineSimilarity(a, b);
    }

    /**
     * 【零拷贝重载】根据指定的 Metric 算法直接从平铺数组 bData 的 bOffset 位置开始计算。
     */
    @Override
    public float calculate(String metric, float[] a, float[] bData, int bOffset, int dim) {
        if (metric == null || "COSINE".equalsIgnoreCase(metric)) {
            return cosineSimilarity(a, bData, bOffset, dim);
        } else if ("DOT_PRODUCT".equalsIgnoreCase(metric) || "IP".equalsIgnoreCase(metric)) {
            return dotProduct(a, bData, bOffset, dim);
        } else if ("EUCLIDEAN".equalsIgnoreCase(metric) || "L2".equalsIgnoreCase(metric)) {
            return euclideanDistance(a, bData, bOffset, dim);
        }
        return cosineSimilarity(a, bData, bOffset, dim);
    }

    /**
     * 余弦相似度计算 (得分范围：[-1.0, 1.0]，越接近 1 表示越相似)。
     * 公式：dot(a, b) / (norm(a) * norm(b))
     */
    @Override
    public float cosineSimilarity(float[] a, float[] b) {
        return cosineSimilarity(a, b, 0, a.length);
    }

    /**
     * 【零拷贝重载】余弦相似度计算（4路循环展开）。
     */
    @Override
    public float cosineSimilarity(float[] a, float[] bData, int bOffset, int dim) {
        float dot = 0.0f;
        float numA = 0.0f;
        float numB = 0.0f;

        int i = 0;
        int upperBound = dim & ~3;

        for (; i < upperBound; i += 4) {
            float a0 = a[i], a1 = a[i + 1], a2 = a[i + 2], a3 = a[i + 3];
            int bIdx = bOffset + i;
            float b0 = bData[bIdx], b1 = bData[bIdx + 1], b2 = bData[bIdx + 2], b3 = bData[bIdx + 3];

            dot += a0 * b0 + a1 * b1 + a2 * b2 + a3 * b3;
            numA += a0 * a0 + a1 * a1 + a2 * a2 + a3 * a3;
            numB += b0 * b0 + b1 * b1 + b2 * b2 + b3 * b3;
        }

        for (; i < dim; i++) {
            float valA = a[i];
            float valB = bData[bOffset + i];
            dot += valA * valB;
            numA += valA * valA;
            numB += valB * valB;
        }

        if (numA == 0.0f || numB == 0.0f) {
            return 0.0f;
        }
        return dot / ((float) (Math.sqrt(numA) * Math.sqrt(numB)));
    }

    /**
     * 向量点积/内积 (Dot Product / Inner Product)。
     */
    @Override
    public float dotProduct(float[] a, float[] b) {
        return dotProduct(a, b, 0, a.length);
    }

    /**
     * 【零拷贝重载】向量点积计算（4路循环展开）。
     */
    @Override
    public float dotProduct(float[] a, float[] bData, int bOffset, int dim) {
        float dot = 0.0f;
        int i = 0;
        int upperBound = dim & ~3;

        for (; i < upperBound; i += 4) {
            int bIdx = bOffset + i;
            dot += a[i] * bData[bIdx]
                 + a[i + 1] * bData[bIdx + 1]
                 + a[i + 2] * bData[bIdx + 2]
                 + a[i + 3] * bData[bIdx + 3];
        }

        for (; i < dim; i++) {
            dot += a[i] * bData[bOffset + i];
        }
        return dot;
    }

    /**
     * 欧氏距离 (Euclidean Distance / L2 Distance)。
     */
    @Override
    public float euclideanDistance(float[] a, float[] b) {
        return euclideanDistance(a, b, 0, a.length);
    }

    /**
     * 【零拷贝重载】欧氏距离计算（4路循环展开）。
     */
    @Override
    public float euclideanDistance(float[] a, float[] bData, int bOffset, int dim) {
        float sum = 0.0f;
        int i = 0;
        int upperBound = dim & ~3;

        for (; i < upperBound; i += 4) {
            int bIdx = bOffset + i;
            float diff0 = a[i] - bData[bIdx];
            float diff1 = a[i + 1] - bData[bIdx + 1];
            float diff2 = a[i + 2] - bData[bIdx + 2];
            float diff3 = a[i + 3] - bData[bIdx + 3];

            sum += diff0 * diff0 + diff1 * diff1 + diff2 * diff2 + diff3 * diff3;
        }

        for (; i < dim; i++) {
            float diff = a[i] - bData[bOffset + i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }
}



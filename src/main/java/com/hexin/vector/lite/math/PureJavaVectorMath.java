package com.hexin.vector.lite.math;

public class PureJavaVectorMath implements VectorMath {

    @Override
    public float calculate(String metric, float[] a, float[] b) {
        if (metric == null || metric.equalsIgnoreCase("COSINE")) {
            return cosineSimilarity(a, b);
        } else if (metric.equalsIgnoreCase("DOT_PRODUCT") || metric.equalsIgnoreCase("IP")) {
            return dotProduct(a, b);
        } else if (metric.equalsIgnoreCase("EUCLIDEAN") || metric.equalsIgnoreCase("L2")) {
            return euclideanDistance(a, b);
        }
        return cosineSimilarity(a, b);
    }

    @Override
    public float cosineSimilarity(float[] a, float[] b) {
        float dot = 0.0f;
        float numA = 0.0f;
        float numB = 0.0f;

        int len = a.length;
        int i = 0;
        int upperBound = len & ~3;

        for (; i < upperBound; i += 4) {
            float a0 = a[i], a1 = a[i + 1], a2 = a[i + 2], a3 = a[i + 3];
            float b0 = b[i], b1 = b[i + 1], b2 = b[i + 2], b3 = b[i + 3];

            dot += a0 * b0 + a1 * b1 + a2 * b2 + a3 * b3;
            numA += a0 * a0 + a1 * a1 + a2 * a2 + a3 * a3;
            numB += b0 * b0 + b1 * b1 + b2 * b2 + b3 * b3;
        }

        for (; i < len; i++) {
            dot += a[i] * b[i];
            numA += a[i] * a[i];
            numB += b[i] * b[i];
        }

        if (numA == 0.0f || numB == 0.0f) {
            return 0.0f;
        }
        return dot / ((float) (Math.sqrt(numA) * Math.sqrt(numB)));
    }

    @Override
    public float dotProduct(float[] a, float[] b) {
        float dot = 0.0f;
        int len = a.length;
        int i = 0;
        int upperBound = len & ~3;

        for (; i < upperBound; i += 4) {
            dot += a[i] * b[i] + a[i + 1] * b[i + 1] + a[i + 2] * b[i + 2] + a[i + 3] * b[i + 3];
        }

        for (; i < len; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    @Override
    public float euclideanDistance(float[] a, float[] b) {
        float sum = 0.0f;
        int len = a.length;
        for (int i = 0; i < len; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }
}

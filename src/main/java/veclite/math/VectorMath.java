package veclite.math;

public interface VectorMath {

    float calculate(String metric, float[] a, float[] b);

    float cosineSimilarity(float[] a, float[] b);

    float dotProduct(float[] a, float[] b);

    float euclideanDistance(float[] a, float[] b);
}

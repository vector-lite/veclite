package com.hexin.vector.lite.model;

import java.io.Serializable;

public class VectorStoreStats implements Serializable {
    private static final long serialVersionUID = 1L;

    private String storeName;
    private int dimension;
    private int docCount;
    private int maxCapacity;
    private String metric;
    private QuantizationType quantization;

    public VectorStoreStats() {
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public int getDocCount() {
        return docCount;
    }

    public void setDocCount(int docCount) {
        this.docCount = docCount;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public QuantizationType getQuantization() {
        return quantization;
    }

    public void setQuantization(QuantizationType quantization) {
        this.quantization = quantization;
    }
}

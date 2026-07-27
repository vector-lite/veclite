package com.hexin.vector.lite.api;

import com.hexin.vector.lite.model.QuantizationType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class VectorStoreDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    private String storeName;
    private int dimension = 512;
    private String metric = "COSINE";
    private int maxCapacity = 100000;
    private String embeddingModel;
    private QuantizationType quantization = QuantizationType.NONE;
    private List<String> indexedMetadataFields = new ArrayList<>();

    public VectorStoreDefinition() {
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

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public QuantizationType getQuantization() {
        return quantization;
    }

    public void setQuantization(QuantizationType quantization) {
        this.quantization = quantization;
    }

    public List<String> getIndexedMetadataFields() {
        return indexedMetadataFields;
    }

    public void setIndexedMetadataFields(List<String> indexedMetadataFields) {
        this.indexedMetadataFields = indexedMetadataFields;
    }
}

package com.hexin.vector.lite.model;

import java.io.Serializable;

public class VectorSearchRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String storeName;
    private SearchMode mode = SearchMode.VECTOR;
    private float[] queryVector;
    private String queryText;
    private int topK = 10;
    private Float minScore;
    private FilterExpression filter;

    public VectorSearchRequest() {
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public SearchMode getMode() {
        return mode;
    }

    public void setMode(SearchMode mode) {
        this.mode = mode;
    }

    public float[] getQueryVector() {
        return queryVector;
    }

    public void setQueryVector(float[] queryVector) {
        this.queryVector = queryVector;
    }

    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public Float getMinScore() {
        return minScore;
    }

    public void setMinScore(Float minScore) {
        this.minScore = minScore;
    }

    public FilterExpression getFilter() {
        return filter;
    }

    public void setFilter(FilterExpression filter) {
        this.filter = filter;
    }
}

package com.hexin.vector.lite.model;

import java.io.Serializable;
import java.util.Map;

public class VectorSearchResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private float score;
    private String text;
    private Map<String, Object> metadata;

    public VectorSearchResult() {
    }

    public VectorSearchResult(String id, float score, String text, Map<String, Object> metadata) {
        this.id = id;
        this.score = score;
        this.text = text;
        this.metadata = metadata;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public float getScore() {
        return score;
    }

    public void setScore(float score) {
        this.score = score;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}

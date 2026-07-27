package com.hexin.vector.lite.model;

import java.io.Serializable;
import java.util.Map;

public class VectorDocument implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private float[] vector;
    private String text;
    private Map<String, Object> metadata;

    public VectorDocument() {
    }

    public VectorDocument(String id, float[] vector, String text, Map<String, Object> metadata) {
        this.id = id;
        this.vector = vector;
        this.text = text;
        this.metadata = metadata;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public float[] getVector() {
        return vector;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
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

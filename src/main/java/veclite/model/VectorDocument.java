package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Map;

@Schema(description = "A document to be stored in a vector store")
public class VectorDocument implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Unique identifier of the document", example = "doc-001")
    private String id;

    @Schema(description = "Pre-computed float vector (mutually exclusive with text-based embedding)")
    private float[] vector;

    @Schema(description = "Raw text content (used for text-based search embedding)", example = "This is a sample document")
    private String text;

    @Schema(description = "Arbitrary key-value metadata for filtering")
    private Map<String, Object> metadata;

    public VectorDocument() {
    }

    public VectorDocument(String id, float[] vector, String text, Map<String, Object> metadata) {
        this.id = id;
        this.vector = vector;
        this.text = text;
        this.metadata = metadata;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public float[] getVector() { return vector; }
    public void setVector(float[] vector) { this.vector = vector; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}

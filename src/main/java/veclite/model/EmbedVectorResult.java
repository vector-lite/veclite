package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.List;

@Schema(description = "Embedding result of a single text")
public class EmbedVectorResult implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Embedding model name", example = "bge-m3")
    private String name;

    @Schema(description = "Resolved model version actually used", example = "1")
    private String version;

    @Schema(description = "Actual vector dimension of the returned vector", example = "1024")
    private int dimension;

    @Schema(description = "The embedding vector")
    private List<Float> vector;

    public EmbedVectorResult() {
    }

    public EmbedVectorResult(String name, String version, int dimension, List<Float> vector) {
        this.name = name;
        this.version = version;
        this.dimension = dimension;
        this.vector = vector;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public List<Float> getVector() { return vector; }
    public void setVector(List<Float> vector) { this.vector = vector; }
}

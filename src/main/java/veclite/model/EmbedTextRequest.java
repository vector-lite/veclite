package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "Request body of single-text embedding against a configured embedding data source")
public class EmbedTextRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Text to embed; must not be blank", example = "如何部署向量检索服务")
    private String text;

    @Schema(description = "Requested output dimension; 0 lets the service decide", example = "0")
    private int dimension;

    public EmbedTextRequest() {
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
}

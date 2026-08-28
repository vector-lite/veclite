package veclite.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "Vector store statistics")
public class VectorStoreStats implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Store name", example = "my-store")
    private String storeName;

    @Schema(description = "Vector dimension", example = "768")
    private int dimension;

    @Schema(description = "Current document count", example = "1234")
    private int docCount;

    @Schema(description = "Maximum capacity", example = "100000")
    private int maxCapacity;

    @Schema(description = "Distance metric", example = "COSINE")
    private String metric;

    @Schema(description = "Quantization type applied")
    private QuantizationType quantization;

    public VectorStoreStats() {}

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public int getDocCount() { return docCount; }
    public void setDocCount(int docCount) { this.docCount = docCount; }
    public int getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(int maxCapacity) { this.maxCapacity = maxCapacity; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public QuantizationType getQuantization() { return quantization; }
    public void setQuantization(QuantizationType quantization) { this.quantization = quantization; }
}

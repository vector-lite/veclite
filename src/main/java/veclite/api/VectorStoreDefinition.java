package veclite.api;

import io.swagger.v3.oas.annotations.media.Schema;
import veclite.model.QuantizationType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "Definition for creating a new vector store")
public class VectorStoreDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "Name of the store", example = "my-index")
    private String storeName;

    @Schema(description = "Vector dimension", example = "768")
    private int dimension = 512;

    @Schema(description = "Distance metric", example = "COSINE", allowableValues = {"COSINE", "EUCLIDEAN", "DOT_PRODUCT"})
    private String metric = "COSINE";

    @Schema(description = "Maximum document capacity", example = "100000")
    private int maxCapacity = 100000;

    @Schema(description = "Embedding model name (from configured models). Bound at creation and immutable afterwards", example = "text-embedding-ada-002")
    private String embeddingModel;

    @Schema(description = "Embedding model version. Defaults to the version in model config when omitted. Immutable after store creation", example = "1")
    private String embeddingModelVersion;

    @Schema(description = "Vector quantization type")
    private QuantizationType quantization = QuantizationType.NONE;

    @Schema(description = "Metadata fields to build index on for fast filtering")
    private List<String> indexedMetadataFields = new ArrayList<>();

    /**
     * v2.4 持久化：最新一次成功刷盘到远端（OSS）时生成的快照版本号。
     * SNAPSHOT_FILE 模式下不写，可为 null。
     */
    private String snapshotVersion;

    public VectorStoreDefinition() {}

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public int getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(int maxCapacity) { this.maxCapacity = maxCapacity; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getEmbeddingModelVersion() { return embeddingModelVersion; }
    public void setEmbeddingModelVersion(String embeddingModelVersion) { this.embeddingModelVersion = embeddingModelVersion; }
    public QuantizationType getQuantization() { return quantization; }
    public void setQuantization(QuantizationType quantization) { this.quantization = quantization; }
    public List<String> getIndexedMetadataFields() { return indexedMetadataFields; }
    public void setIndexedMetadataFields(List<String> indexedMetadataFields) { this.indexedMetadataFields = indexedMetadataFields; }
    public String getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(String snapshotVersion) { this.snapshotVersion = snapshotVersion; }
}

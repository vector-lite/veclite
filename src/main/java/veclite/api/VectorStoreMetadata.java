package veclite.api;

import veclite.model.QuantizationType;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量库 Store 级元数据（单一真相源持久化模式下的 Store 注册信息）。
 * <p>
 * 一个 Store 对应一条元数据：既承载 {@link VectorStoreDefinition} 的全部配置，
 * 也记录 SQ8 冻结态的量化参数与增量同步水位。
 */
public class VectorStoreMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String storeName;
    private int dimension;
    private String metric = "COSINE";
    private int maxCapacity;
    private String embeddingModel;
    private String embeddingModelVersion;
    private QuantizationType quantization = QuantizationType.NONE;
    private List<String> indexedMetadataFields = new ArrayList<>();

    /** 有效向量条数（汇总值，供管理侧展示，不作为正确性依据） */
    private int activeCount;

    /** SQ8 冻结态逐维量化参数（与文档 vector_format=SQ8 配套，装载时经 restoreFrozenParams 注入） */
    private float[] sq8MinPerDim;
    private float[] sq8ScalePerDim;

    private Instant createdAt;
    private Instant updatedAt;

    /** 增量同步水位：内存投影已消费到的文档 updatedAt；全量装载时以装载开始时间建立基线 */
    private Instant syncWatermark;

    public static VectorStoreMetadata fromDefinition(VectorStoreDefinition definition) {
        VectorStoreMetadata metadata = new VectorStoreMetadata();
        metadata.setStoreName(definition.getStoreName());
        metadata.setDimension(definition.getDimension());
        metadata.setMetric(definition.getMetric());
        metadata.setMaxCapacity(definition.getMaxCapacity());
        metadata.setEmbeddingModel(definition.getEmbeddingModel());
        metadata.setEmbeddingModelVersion(definition.getEmbeddingModelVersion());
        metadata.setQuantization(definition.getQuantization());
        metadata.setIndexedMetadataFields(definition.getIndexedMetadataFields());
        return metadata;
    }

    public VectorStoreDefinition toDefinition() {
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(storeName);
        definition.setDimension(dimension);
        definition.setMetric(metric);
        definition.setMaxCapacity(maxCapacity);
        definition.setEmbeddingModel(embeddingModel);
        definition.setEmbeddingModelVersion(embeddingModelVersion);
        definition.setQuantization(quantization);
        definition.setIndexedMetadataFields(indexedMetadataFields != null ? indexedMetadataFields : new ArrayList<>());
        return definition;
    }

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
    public int getActiveCount() { return activeCount; }
    public void setActiveCount(int activeCount) { this.activeCount = activeCount; }
    public float[] getSq8MinPerDim() { return sq8MinPerDim; }
    public void setSq8MinPerDim(float[] sq8MinPerDim) { this.sq8MinPerDim = sq8MinPerDim; }
    public float[] getSq8ScalePerDim() { return sq8ScalePerDim; }
    public void setSq8ScalePerDim(float[] sq8ScalePerDim) { this.sq8ScalePerDim = sq8ScalePerDim; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getSyncWatermark() { return syncWatermark; }
    public void setSyncWatermark(Instant syncWatermark) { this.syncWatermark = syncWatermark; }
}

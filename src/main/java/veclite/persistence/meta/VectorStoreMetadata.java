package veclite.persistence.meta;

import veclite.model.QuantizationType;

import java.time.Instant;
import java.util.List;

/**
 * v2.4 hybrid persistence: 向量库元数据
 * 对应 veclite_store_meta 表（schema/veclite_store_meta.sql）
 * 仅存配置与快照指针，不存向量/文档正文。
 */
public class VectorStoreMetadata {

    private String storeName;
    private int dimension;
    private String metric;
    private int maxCapacity;
    private String embeddingModel;
    private String embeddingModelVersion;
    private QuantizationType quantization;
    private List<String> indexedMetadataFields;

    private byte[] sq8MinPerDim;
    private byte[] sq8ScalePerDim;

    private String latestSnapshotVersion;
    private String latestSnapshotOssPath;
    private int activeCount;
    private Instant createdAt;
    private Instant updatedAt;

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
    public byte[] getSq8MinPerDim() { return sq8MinPerDim; }
    public void setSq8MinPerDim(byte[] sq8MinPerDim) { this.sq8MinPerDim = sq8MinPerDim; }
    public byte[] getSq8ScalePerDim() { return sq8ScalePerDim; }
    public void setSq8ScalePerDim(byte[] sq8ScalePerDim) { this.sq8ScalePerDim = sq8ScalePerDim; }
    public String getLatestSnapshotVersion() { return latestSnapshotVersion; }
    public void setLatestSnapshotVersion(String latestSnapshotVersion) { this.latestSnapshotVersion = latestSnapshotVersion; }
    public String getLatestSnapshotOssPath() { return latestSnapshotOssPath; }
    public void setLatestSnapshotOssPath(String latestSnapshotOssPath) { this.latestSnapshotOssPath = latestSnapshotOssPath; }
    public int getActiveCount() { return activeCount; }
    public void setActiveCount(int activeCount) { this.activeCount = activeCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

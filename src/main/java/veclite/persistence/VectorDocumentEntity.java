package veclite.persistence;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Map;

/**
 * 存储无关的向量文档实体（文档持久化真相源中的一行）。
 * <p>
 * 携带文档 ID、正文、元数据与向量本体，向上由 {@link DocumentBackedPersistence} 编排，
 * 向下由 {@link VectorDocumentRepository} 各实现负责与具体存储格式互转。
 * 向量按 {@link #format} 解释：FLOAT32 时 {@link #vector} 有效，SQ8 时 {@link #sq8Vector} 有效。
 */
public class VectorDocumentEntity {
    public static final VectorStorageFormat DEFAULT_FORMAT = VectorStorageFormat.FLOAT32;

    private String docId;
    private String text;
    private Map<String, Object> metadata;
    private VectorStorageFormat format = DEFAULT_FORMAT;
    /** FLOAT32 格式的原始向量 */
    private float[] vector;
    /** SQ8 格式的量化字节（1 字节/维） */
    private byte[] sq8Vector;
    private int vectorDim;
    private String embeddingModel;
    private Instant updatedAt;

    /**
     * 将 Float32 向量序列化为小端字节数组（4 字节/维，无任何额外膨胀）。
     * Bit 位原样保留（含 NaN/Infinity），编解码为纯内存操作。
     */
    public static byte[] encodeVector(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** {@link #encodeVector(float[])} 的逆操作，字节数必须是 4 的整数倍 */
    public static float[] decodeVector(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length % 4 != 0) {
            throw new IllegalArgumentException("Encoded vector byte length must be a multiple of 4, actual: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    public static VectorDocumentEntity float32(String docId, String text, Map<String, Object> metadata,
                                               float[] vector, String embeddingModel) {
        VectorDocumentEntity entity = new VectorDocumentEntity();
        entity.setDocId(docId);
        entity.setText(text);
        entity.setMetadata(metadata);
        entity.setFormat(VectorStorageFormat.FLOAT32);
        entity.setVector(vector);
        entity.setVectorDim(vector != null ? vector.length : 0);
        entity.setEmbeddingModel(embeddingModel);
        return entity;
    }

    public static VectorDocumentEntity sq8(String docId, String text, Map<String, Object> metadata,
                                           byte[] sq8Vector, int dimension, String embeddingModel) {
        VectorDocumentEntity entity = new VectorDocumentEntity();
        entity.setDocId(docId);
        entity.setText(text);
        entity.setMetadata(metadata);
        entity.setFormat(VectorStorageFormat.SQ8);
        entity.setSq8Vector(sq8Vector);
        entity.setVectorDim(dimension);
        entity.setEmbeddingModel(embeddingModel);
        return entity;
    }

    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public VectorStorageFormat getFormat() { return format; }
    public void setFormat(VectorStorageFormat format) { this.format = format; }
    public float[] getVector() { return vector; }
    public void setVector(float[] vector) { this.vector = vector; }
    public byte[] getSq8Vector() { return sq8Vector; }
    public void setSq8Vector(byte[] sq8Vector) { this.sq8Vector = sq8Vector; }
    public int getVectorDim() { return vectorDim; }
    public void setVectorDim(int vectorDim) { this.vectorDim = vectorDim; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

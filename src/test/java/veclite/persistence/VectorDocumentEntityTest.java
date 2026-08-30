package veclite.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量文档实体编解码单元测试：小端序列化的位级保真与边界条件。
 */
class VectorDocumentEntityTest {

    @Test
    @DisplayName("Float32 向量编码为小端字节，与手工 ByteBuffer 结果位级一致")
    void encodeShouldUseLittleEndian() {
        float[] vector = {0.1f, -3.5f, Float.MAX_VALUE, Float.MIN_VALUE};
        byte[] encoded = VectorDocumentEntity.encodeVector(vector);

        ByteBuffer expected = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            expected.putFloat(v);
        }
        assertArrayEquals(expected.array(), encoded);
        assertEquals(vector.length * 4, encoded.length);
    }

    @Test
    @DisplayName("编码-解码往返应位级保真（含 NaN/Infinity，不经过任何数值转换）")
    void roundTripShouldPreserveBits() {
        float[] vector = {0.1f, -273.15f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f};
        float[] decoded = VectorDocumentEntity.decodeVector(VectorDocumentEntity.encodeVector(vector));

        assertArrayEquals(vector, decoded);
        for (int i = 0; i < vector.length; i++) {
            assertEquals(Float.floatToIntBits(vector[i]), Float.floatToIntBits(decoded[i]),
                    "bit-level mismatch at index " + i);
        }
    }

    @Test
    @DisplayName("解码非法字节长度（非 4 的倍数）应抛出 IllegalArgumentException；空数组退化为空向量")
    void decodeShouldRejectBadLength() {
        assertThrows(IllegalArgumentException.class, () -> VectorDocumentEntity.decodeVector(new byte[5]));
        assertEquals(0, VectorDocumentEntity.decodeVector(new byte[0]).length);
    }

    @Test
    @DisplayName("null 向量的编解码应返回 null（稀疏文档允许无向量落库）")
    void nullVectorShouldStayNull() {
        assertNull(VectorDocumentEntity.encodeVector(null));
        assertNull(VectorDocumentEntity.decodeVector(null));
    }

    @Test
    @DisplayName("float32 工厂应填充格式、维度与 embedding 模型")
    void float32FactoryShouldFillFields() {
        VectorDocumentEntity entity = VectorDocumentEntity.float32(
                "doc-1", "text", Map.of("category", "a"), new float[]{1f, 2f}, "model-x");

        assertEquals(VectorStorageFormat.FLOAT32, entity.getFormat());
        assertEquals("doc-1", entity.getDocId());
        assertEquals(2, entity.getVectorDim());
        assertEquals("model-x", entity.getEmbeddingModel());
        assertTrue(entity.getSq8Vector() == null);
    }

    @Test
    @DisplayName("大向量编解码往返（1536 维）应保持内容一致")
    void largeVectorRoundTrip() {
        float[] vector = new float[1536];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) Math.sin(i) * 0.01f;
        }
        assertArrayEquals(vector, VectorDocumentEntity.decodeVector(VectorDocumentEntity.encodeVector(vector)));
    }

    @Test
    @DisplayName("SQ8 工厂应记录量化字节与维度，且不影响默认格式常量")
    void sq8FactoryShouldFillQuantizedBytes() {
        byte[] quantized = new byte[4];
        quantized[0] = 7;
        VectorDocumentEntity entity = VectorDocumentEntity.sq8("doc-2", "t", Map.of(), quantized, 4, "model-x");

        assertEquals(VectorStorageFormat.SQ8, entity.getFormat());
        assertArrayEquals(quantized, entity.getSq8Vector());
        assertEquals(4, entity.getVectorDim());
        assertNull(entity.getVector());
        assertNotEquals(VectorStorageFormat.SQ8, VectorDocumentEntity.DEFAULT_FORMAT);
    }
}

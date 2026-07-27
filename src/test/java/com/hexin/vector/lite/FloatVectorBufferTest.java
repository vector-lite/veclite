package com.hexin.vector.lite;

import com.hexin.vector.lite.engine.FloatVectorBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FloatVectorBufferTest {

    @Test
    public void testAppendAndGet() {
        FloatVectorBuffer buffer = new FloatVectorBuffer(4, 10);
        float[] vec1 = new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        int offset = buffer.append(vec1);
        assertEquals(0, offset);
        assertEquals(1, buffer.getSize());

        float[] read = buffer.getVector(offset);
        assertArrayEquals(vec1, read, 0.0001f);
    }

    @Test
    public void testDimensionMismatch() {
        FloatVectorBuffer buffer = new FloatVectorBuffer(4, 10);
        assertThrows(IllegalArgumentException.class, () -> buffer.append(new float[]{0.1f, 0.2f}));
    }
}

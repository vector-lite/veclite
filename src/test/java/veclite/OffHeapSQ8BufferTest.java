package veclite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import veclite.engine.OffHeapSQ8Buffer;

import static org.junit.jupiter.api.Assertions.*;

public class OffHeapSQ8BufferTest {

    private OffHeapSQ8Buffer buffer;
    private final int dim = 4;

    @BeforeEach
    public void setUp() {
        buffer = new OffHeapSQ8Buffer(dim, 4);
    }

    @Test
    public void testAppendAndGet() {
        byte[] v1 = new byte[]{1, 2, 3, 4};
        byte[] v2 = new byte[]{5, 6, 7, 8};

        int off1 = buffer.append(v1);
        int off2 = buffer.append(v2);

        assertEquals(0, off1);
        assertEquals(1, off2);
        assertEquals(2, buffer.getSize());

        byte[] fetched1 = buffer.getVector(off1);
        assertArrayEquals(v1, fetched1);

        byte[] fetched2 = buffer.getVector(off2);
        assertArrayEquals(v2, fetched2);
    }

    @Test
    public void testUpdateAt() {
        byte[] v1 = new byte[]{1, 2, 3, 4};
        int off1 = buffer.append(v1);

        byte[] v1Updated = new byte[]{10, 20, 30, 40};
        buffer.updateAt(off1, v1Updated);

        assertArrayEquals(v1Updated, buffer.getVector(off1));
    }

    @Test
    public void testAutoCapacityExtension() {
        for (int i = 0; i < 100; i++) {
            byte[] vec = new byte[]{(byte) i, (byte) (i + 1), (byte) (i + 2), (byte) (i + 3)};
            int offset = buffer.append(vec);
            assertEquals(i, offset);
        }
        assertEquals(100, buffer.getSize());

        for (int i = 0; i < 100; i++) {
            byte[] expected = new byte[]{(byte) i, (byte) (i + 1), (byte) (i + 2), (byte) (i + 3)};
            assertArrayEquals(expected, buffer.getVector(i));
        }
    }
}

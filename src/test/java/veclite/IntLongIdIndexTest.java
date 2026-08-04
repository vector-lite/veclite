package veclite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import veclite.engine.IntLongIdIndex;

import static org.junit.jupiter.api.Assertions.*;

public class IntLongIdIndexTest {

    private IntLongIdIndex index;

    @BeforeEach
    public void setUp() {
        index = new IntLongIdIndex(16);
    }

    @Test
    public void testPutAndGet() {
        index.put("doc_1", 0);
        index.put("doc_2", 1);
        index.put("doc_3", 2);

        assertEquals(0, index.getOffset("doc_1"));
        assertEquals(1, index.getOffset("doc_2"));
        assertEquals(2, index.getOffset("doc_3"));
        assertNull(index.getOffset("non_existent"));

        assertEquals("doc_1", index.getId(0));
        assertEquals("doc_2", index.getId(1));
        assertEquals("doc_3", index.getId(2));
        assertNull(index.getId(999));
        assertEquals(3, index.size());
    }

    @Test
    public void testUpdateOffset() {
        index.put("doc_1", 0);
        assertEquals(0, index.getOffset("doc_1"));

        index.put("doc_1", 10);
        assertEquals(10, index.getOffset("doc_1"));
        assertEquals("doc_1", index.getId(10));
    }

    @Test
    public void testRemove() {
        index.put("doc_1", 0);
        index.put("doc_2", 1);

        assertTrue(index.containsId("doc_1"));
        Integer removedOffset = index.remove("doc_1");
        assertEquals(0, removedOffset);
        assertFalse(index.containsId("doc_1"));
        assertNull(index.getOffset("doc_1"));
        assertNull(index.getId(0));
        assertEquals(1, index.size());
    }

    @Test
    public void testAutoExpansion() {
        for (int i = 0; i < 1000; i++) {
            index.put("doc_" + i, i);
        }
        assertEquals(1000, index.size());
        for (int i = 0; i < 1000; i++) {
            assertEquals(i, index.getOffset("doc_" + i));
            assertEquals("doc_" + i, index.getId(i));
        }
    }
}

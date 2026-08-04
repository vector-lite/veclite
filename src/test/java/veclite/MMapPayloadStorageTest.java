package veclite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import veclite.engine.LocalVectorStore;
import veclite.engine.MMapPayloadStorage;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MMapPayloadStorageTest {

    private MMapPayloadStorage payloadStorage;
    private final String testDir = "./build/tmp/mmap_test";

    @BeforeEach
    public void setUp() {
        deleteDir(new File(testDir));
        payloadStorage = new MMapPayloadStorage("test_store", testDir, 16);
    }

    @AfterEach
    public void tearDown() {
        if (payloadStorage != null) {
            payloadStorage.close();
        }
        deleteDir(new File(testDir));
    }

    @Test
    public void testPutAndGet() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("category", "tech");
        meta.put("score", 95);

        payloadStorage.put(0, "doc_100", "Hello world text", meta);

        assertEquals("doc_100", payloadStorage.getId(0));
        assertEquals("Hello world text", payloadStorage.getText(0));
        assertEquals("tech", payloadStorage.getMetadata(0).get("category"));

        LocalVectorStore.DocumentPayload payload = payloadStorage.get(0);
        assertNotNull(payload);
        assertEquals("doc_100", payload.getId());
        assertEquals("Hello world text", payload.getText());
        assertEquals(95, ((Number) payload.getMetadata().get("score")).intValue());
    }

    private void deleteDir(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteDir(f);
                    else f.delete();
                }
            }
            dir.delete();
        }
    }
}

package veclite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorStore;
import veclite.model.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class LocalVectorStoreV23Test {

    private LocalVectorStore store;
    private VectorLiteProperties properties;

    @BeforeEach
    public void setUp() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("v23_test_store");
        def.setDimension(4);
        def.setMetric("COSINE");
        def.setMaxCapacity(1000);
        def.setQuantization(QuantizationType.SQ8);
        def.setIndexedMetadataFields(Collections.singletonList("category"));

        properties = new VectorLiteProperties();
        properties.getStorage().getOffHeap().setEnabled(true);
        properties.getStorage().getPayload().setMode(PayloadMode.MMAP);
        properties.getStorage().getSnapshotFile().setBasePath("./build/tmp/v23_store");

        store = new LocalVectorStore(def, properties);
    }

    @Test
    public void testV23OffHeapAndMMapUpsertAndSearch() {
        assertTrue(store.isSQ8Enabled());
        assertTrue(store.isOffHeapEnabled());

        for (int i = 0; i < 20; i++) {
            VectorDocument doc = new VectorDocument();
            doc.setId("doc_" + i);
            doc.setText("Sample text content for document " + i);
            doc.setVector(new float[]{0.1f * (i % 5), 0.2f, 0.3f, 0.4f});
            Map<String, Object> meta = new HashMap<>();
            meta.put("category", i % 2 == 0 ? "A" : "B");
            doc.setMetadata(meta);
            store.upsert(doc);
        }

        assertEquals(20, store.getActiveCount());

        VectorSearchRequest searchReq = new VectorSearchRequest();
        searchReq.setQueryVector(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
        searchReq.setTopK(5);
        FilterExpression filter = FilterExpression.eq("category", "A");
        searchReq.setFilter(filter);

        List<VectorSearchResult> results = store.search(searchReq);
        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 5);

        for (VectorSearchResult result : results) {
            assertNotNull(result.getId());
            assertNotNull(result.getText());
            assertNotNull(result.getMetadata());
            assertEquals("A", result.getMetadata().get("category"));
        }
    }

    @Test
    public void testDeleteByIds() {
        VectorDocument doc = new VectorDocument();
        doc.setId("delete_me");
        doc.setText("to be deleted");
        doc.setVector(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        store.upsert(doc);

        assertEquals(1, store.getActiveCount());

        DeleteResult result = store.deleteByIds(Collections.singletonList("delete_me"));
        assertEquals(1, result.getDeletedCount());
        assertEquals(0, store.getActiveCount());
    }
}

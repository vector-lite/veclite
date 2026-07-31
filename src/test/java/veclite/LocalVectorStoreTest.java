package veclite;

import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;
import veclite.model.FilterExpression;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LocalVectorStoreTest {

    @Test
    public void testUpsertAndSearch() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("test_store");
        def.setDimension(3);
        def.setMetric("COSINE");
        def.setMaxCapacity(100);

        LocalVectorStore store = new LocalVectorStore(def);

        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("category", "tech");
        VectorDocument doc1 = new VectorDocument("doc1", new float[]{1.0f, 0.0f, 0.0f}, "tech doc", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("category", "news");
        VectorDocument doc2 = new VectorDocument("doc2", new float[]{0.0f, 1.0f, 0.0f}, "news doc", meta2);

        store.upsert(doc1);
        store.upsert(doc2);

        assertEquals(2, store.getActiveCount());

        VectorSearchRequest req = new VectorSearchRequest();
        req.setQueryVector(new float[]{0.9f, 0.1f, 0.0f});
        req.setTopK(2);

        List<VectorSearchResult> results = store.search(req);
        assertEquals(2, results.size());
        assertEquals("doc1", results.get(0).getId());

        req.setFilter(FilterExpression.eq("category", "news"));
        List<VectorSearchResult> filtered = store.search(req);
        assertEquals(1, filtered.size());
        assertEquals("doc2", filtered.get(0).getId());
    }
}

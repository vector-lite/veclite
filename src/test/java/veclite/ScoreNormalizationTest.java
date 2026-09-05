package veclite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ScoreNormalizationTest {

    @Test
    @DisplayName("COSINE metric score normalization and minScore filtering")
    public void testCosineNormalization() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("test_cosine_norm");
        def.setDimension(2);
        def.setMetric("COSINE");
        def.setMaxCapacity(10);

        LocalVectorStore store = new LocalVectorStore(def);
        store.upsert(new VectorDocument("identical", new float[]{1.0f, 0.0f}, "identical", Collections.emptyMap()));
        store.upsert(new VectorDocument("orthogonal", new float[]{0.0f, 1.0f}, "orthogonal", Collections.emptyMap()));
        store.upsert(new VectorDocument("opposite", new float[]{-1.0f, 0.0f}, "opposite", Collections.emptyMap()));

        float[] query = new float[]{1.0f, 0.0f};

        // 1. Default without score normalization: raw cosine [-1, 1]
        VectorSearchRequest rawReq = new VectorSearchRequest();
        rawReq.setQueryVector(query);
        rawReq.setTopK(3);
        List<VectorSearchResult> rawResults = store.search(rawReq);
        assertEquals(3, rawResults.size());
        assertEquals("identical", rawResults.get(0).getId());
        assertEquals(1.0f, rawResults.get(0).getScore(), 1e-4f);
        assertEquals("orthogonal", rawResults.get(1).getId());
        assertEquals(0.0f, rawResults.get(1).getScore(), 1e-4f);
        assertEquals("opposite", rawResults.get(2).getId());
        assertEquals(-1.0f, rawResults.get(2).getScore(), 1e-4f);

        // 2. With normalizeScore = true: mapped to (1 + cos) / 2
        VectorSearchRequest normReq = new VectorSearchRequest();
        normReq.setQueryVector(query);
        normReq.setTopK(3);
        normReq.setNormalizeScore(true);
        List<VectorSearchResult> normResults = store.search(normReq);
        assertEquals(3, normResults.size());
        assertEquals("identical", normResults.get(0).getId());
        assertEquals(1.0f, normResults.get(0).getScore(), 1e-4f);
        assertEquals("orthogonal", normResults.get(1).getId());
        assertEquals(0.5f, normResults.get(1).getScore(), 1e-4f);
        assertEquals("opposite", normResults.get(2).getId());
        assertEquals(0.0f, normResults.get(2).getScore(), 1e-4f);

        // 3. With normalizeScore = true and minScore = 0.75f (only identical kept)
        VectorSearchRequest filterReq = new VectorSearchRequest();
        filterReq.setQueryVector(query);
        filterReq.setTopK(3);
        filterReq.setNormalizeScore(true);
        filterReq.setMinScore(0.75f);
        List<VectorSearchResult> filterResults = store.search(filterReq);
        assertEquals(1, filterResults.size());
        assertEquals("identical", filterResults.get(0).getId());
    }

    @Test
    @DisplayName("EUCLIDEAN metric score normalization and minScore filtering")
    public void testEuclideanNormalization() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("test_euclidean_norm");
        def.setDimension(2);
        def.setMetric("EUCLIDEAN");
        def.setMaxCapacity(10);

        LocalVectorStore store = new LocalVectorStore(def);
        store.upsert(new VectorDocument("doc0", new float[]{1.0f, 0.0f}, "doc0", Collections.emptyMap())); // dist=0
        store.upsert(new VectorDocument("doc1", new float[]{2.0f, 0.0f}, "doc1", Collections.emptyMap())); // dist=1
        store.upsert(new VectorDocument("doc3", new float[]{4.0f, 0.0f}, "doc3", Collections.emptyMap())); // dist=3

        float[] query = new float[]{1.0f, 0.0f};

        // 1. Default raw Euclidean distance
        VectorSearchRequest rawReq = new VectorSearchRequest();
        rawReq.setQueryVector(query);
        rawReq.setTopK(3);
        List<VectorSearchResult> rawResults = store.search(rawReq);
        assertEquals(3, rawResults.size());
        assertEquals("doc0", rawResults.get(0).getId());
        assertEquals(0.0f, rawResults.get(0).getScore(), 1e-4f);
        assertEquals("doc1", rawResults.get(1).getId());
        assertEquals(1.0f, rawResults.get(1).getScore(), 1e-4f);
        assertEquals("doc3", rawResults.get(2).getId());
        assertEquals(3.0f, rawResults.get(2).getScore(), 1e-4f);

        // 2. Normalized score: 1 / (1 + distance)
        VectorSearchRequest normReq = new VectorSearchRequest();
        normReq.setQueryVector(query);
        normReq.setTopK(3);
        normReq.setNormalizeScore(true);
        List<VectorSearchResult> normResults = store.search(normReq);
        assertEquals(3, normResults.size());
        assertEquals("doc0", normResults.get(0).getId());
        assertEquals(1.0f, normResults.get(0).getScore(), 1e-4f);  // 1 / (1 + 0) = 1.0
        assertEquals("doc1", normResults.get(1).getId());
        assertEquals(0.5f, normResults.get(1).getScore(), 1e-4f);  // 1 / (1 + 1) = 0.5
        assertEquals("doc3", normResults.get(2).getId());
        assertEquals(0.25f, normResults.get(2).getScore(), 1e-4f); // 1 / (1 + 3) = 0.25

        // 3. minScore = 0.4f (should filter out doc3=0.25)
        VectorSearchRequest filterReq = new VectorSearchRequest();
        filterReq.setQueryVector(query);
        filterReq.setTopK(3);
        filterReq.setNormalizeScore(true);
        filterReq.setMinScore(0.4f);
        List<VectorSearchResult> filterResults = store.search(filterReq);
        assertEquals(2, filterResults.size());
        assertEquals("doc0", filterResults.get(0).getId());
        assertEquals("doc1", filterResults.get(1).getId());
    }

    @Test
    @DisplayName("DOT_PRODUCT metric score normalization")
    public void testDotProductNormalization() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("test_dot_norm");
        def.setDimension(2);
        def.setMetric("DOT_PRODUCT");
        def.setMaxCapacity(10);

        LocalVectorStore store = new LocalVectorStore(def);
        store.upsert(new VectorDocument("doc1", new float[]{1.0f, 0.0f}, "doc1", Collections.emptyMap()));
        store.upsert(new VectorDocument("doc2", new float[]{0.0f, 1.0f}, "doc2", Collections.emptyMap()));

        float[] query = new float[]{1.0f, 0.0f};

        VectorSearchRequest normReq = new VectorSearchRequest();
        normReq.setQueryVector(query);
        normReq.setTopK(2);
        normReq.setNormalizeScore(true);
        List<VectorSearchResult> results = store.search(normReq);
        assertEquals(2, results.size());
        assertEquals("doc1", results.get(0).getId());
        assertEquals(1.0f, results.get(0).getScore(), 1e-4f);
        assertEquals("doc2", results.get(1).getId());
        assertEquals(0.5f, results.get(1).getScore(), 1e-4f);
    }

    @Test
    @DisplayName("Custom score expression: score * 2.0 - 1.0 and (score + 1.0) / 2.0")
    public void testCustomScoreExpression() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("test_custom_expr");
        def.setDimension(2);
        def.setMetric("COSINE");
        def.setMaxCapacity(10);

        LocalVectorStore store = new LocalVectorStore(def);
        store.upsert(new VectorDocument("doc1", new float[]{1.0f, 0.0f}, "doc1", Collections.emptyMap())); // raw = 1.0
        store.upsert(new VectorDocument("doc2", new float[]{0.0f, 1.0f}, "doc2", Collections.emptyMap())); // raw = 0.0
        store.upsert(new VectorDocument("doc3", new float[]{-1.0f, 0.0f}, "doc3", Collections.emptyMap())); // raw = -1.0

        float[] query = new float[]{1.0f, 0.0f};

        // 1. Expression: score * 2.0 - 1.0
        VectorSearchRequest req1 = new VectorSearchRequest();
        req1.setQueryVector(query);
        req1.setTopK(3);
        req1.setScoreExpression("score * 2.0 - 1.0");
        List<VectorSearchResult> results1 = store.search(req1);
        assertEquals(3, results1.size());
        assertEquals(1.0f * 2.0f - 1.0f, results1.get(0).getScore(), 1e-4f); // 1.0
        assertEquals(0.0f * 2.0f - 1.0f, results1.get(1).getScore(), 1e-4f); // -1.0
        assertEquals(-1.0f * 2.0f - 1.0f, results1.get(2).getScore(), 1e-4f); // -3.0

        // 2. Expression: (score + 1.0) / 2.0
        VectorSearchRequest req2 = new VectorSearchRequest();
        req2.setQueryVector(query);
        req2.setTopK(3);
        req2.setScoreExpression("(score + 1.0) / 2.0");
        List<VectorSearchResult> results2 = store.search(req2);
        assertEquals(3, results2.size());
        assertEquals(1.0f, results2.get(0).getScore(), 1e-4f);
        assertEquals(0.5f, results2.get(1).getScore(), 1e-4f);
        assertEquals(0.0f, results2.get(2).getScore(), 1e-4f);

        // 3. Expression with minScore filtering: score * 100 with minScore = 50.0f
        VectorSearchRequest req3 = new VectorSearchRequest();
        req3.setQueryVector(query);
        req3.setTopK(3);
        req3.setScoreExpression("score * 100");
        req3.setMinScore(50.0f);
        List<VectorSearchResult> results3 = store.search(req3);
        assertEquals(1, results3.size());
        assertEquals("doc1", results3.get(0).getId());
        assertEquals(100.0f, results3.get(0).getScore(), 1e-4f);
    }

    @Test
    @DisplayName("ScoreExpressionEvaluator syntax checks and mathematical functions")
    public void testScoreExpressionEvaluator() {
        assertEquals(15.0f, veclite.math.ScoreExpressionEvaluator.compile("score * 2 + 5").evaluate(5.0f), 1e-4f);
        assertEquals(2.0f, veclite.math.ScoreExpressionEvaluator.compile("sqrt(score)").evaluate(4.0f), 1e-4f);
        assertEquals(10.0f, veclite.math.ScoreExpressionEvaluator.compile("max(score, 10)").evaluate(3.0f), 1e-4f);
        assertEquals(3.0f, veclite.math.ScoreExpressionEvaluator.compile("min(score, 10)").evaluate(3.0f), 1e-4f);
        assertEquals(8.0f, veclite.math.ScoreExpressionEvaluator.compile("2 ^ 3").evaluate(0.0f), 1e-4f);

        // Invalid expressions should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> veclite.math.ScoreExpressionEvaluator.compile("score * "));
        assertThrows(IllegalArgumentException.class, () -> veclite.math.ScoreExpressionEvaluator.compile("(score + 1"));
        assertThrows(IllegalArgumentException.class, () -> veclite.math.ScoreExpressionEvaluator.compile("unknown_func(score)"));
    }
}
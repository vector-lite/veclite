package veclite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorStore;
import veclite.model.FilterExpression;
import veclite.model.QuantizationType;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import veclite.persistence.SnapshotFileStorage;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 针对 V2.4 bug 修复的回归测试：
 * 1. Per-Dimension 校准冻结式量化：分布漂移后老数据精度不劣化
 * 2. upsert 更新 metadata 后倒排位图不残留旧值
 * 3. SQ8 快照往返不产生精度累积衰减（多次 save/load 后结果一致）
 * 4. SQ8 模式下 EUCLIDEAN 度量正确生效
 */
public class BugFixRegressionTest {

    private LocalVectorStore newSQ8Store(String name, int dim, List<String> indexedFields) {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName(name);
        def.setDimension(dim);
        def.setMaxCapacity(100000);
        def.setMetric("COSINE");
        def.setQuantization(QuantizationType.SQ8);
        def.setIndexedMetadataFields(indexedFields);
        return new LocalVectorStore(def);
    }

    private VectorSearchRequest request(float[] query, int topK) {
        VectorSearchRequest req = new VectorSearchRequest();
        req.setQueryVector(query);
        req.setTopK(topK);
        return req;
    }

    @Test
    public void testPerDimQuantizationUnderDistributionDrift() {
        int dim = 16;
        LocalVectorStore store = newSQ8Store("drift_store", dim, Collections.emptyList());

        // 第一批：单位化随机向量（决定校准参数）
        Random random = new Random(7);
        List<float[]> firstBatch = new ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            float[] v = randVec(random, dim);
            firstBatch.add(v);
            store.upsert(new VectorDocument("small_" + i, v, "t", new HashMap<>()));
        }
        assertTrue(store.isSQ8Frozen(), "超过校准样本数后应冻结量化参数");

        float[] query = firstBatch.get(500);
        Map<String, Float> scoresBeforeDrift = idToScore(store.search(request(query, 1100)));

        // 第二批：100 倍尺度的向量，模拟线上分布漂移
        // （旧实现会因全局 min/max 漂移破坏第一批已量化数据的解码一致性）
        for (int i = 0; i < 50; i++) {
            float[] v = randVec(random, dim);
            for (int d = 0; d < dim; d++) {
                v[d] *= 100.0f;
            }
            store.upsert(new VectorDocument("big_" + i, v, "t", new HashMap<>()));
        }

        // 冻结的参数不可变：分布漂移后，所有老数据向量的打分必须与漂移前完全一致
        List<VectorSearchResult> afterDrift = store.search(request(query, 1150));
        assertEquals("small_500", afterDrift.get(0).getId(), "应精确召回自身");
        Map<String, Float> scoresAfterDrift = idToScore(afterDrift);
        for (Map.Entry<String, Float> e : scoresBeforeDrift.entrySet()) {
            assertEquals(e.getValue(), scoresAfterDrift.get(e.getKey()),
                    "漂移后老向量 [" + e.getKey() + "] 的得分不应改变");
        }
    }

    private Map<String, Float> idToScore(List<VectorSearchResult> results) {
        Map<String, Float> map = new LinkedHashMap<>();
        for (VectorSearchResult r : results) {
            map.put(r.getId(), r.getScore());
        }
        return map;
    }

    @Test
    public void testMetadataUpdateDoesNotLeaveStaleInvertedIndex() {
        LocalVectorStore store = newSQ8Store("meta_store", 8, Collections.singletonList("category"));

        Map<String, Object> oldMeta = new HashMap<>();
        oldMeta.put("category", "news");
        store.upsert(new VectorDocument("d1", normVec(8, 0.3f), "t", oldMeta));

        // 更新为 sport，旧的 news 位图必须被清除
        Map<String, Object> newMeta = new HashMap<>();
        newMeta.put("category", "sport");
        store.upsert(new VectorDocument("d1", normVec(8, 0.3f), "t2", newMeta));

        FilterExpression staleFilter = new FilterExpression();
        staleFilter.setField("category");
        staleFilter.setOperator(FilterExpression.Operator.EQ);
        staleFilter.setValue("news");
        VectorSearchRequest req = request(normVec(8, 0.3f), 10);
        req.setFilter(staleFilter);
        List<VectorSearchResult> staleResults = store.search(req);
        assertTrue(staleResults.isEmpty(), "更新 metadata 后按旧值过滤不应再命中该文档");

        FilterExpression newFilter = new FilterExpression();
        newFilter.setField("category");
        newFilter.setOperator(FilterExpression.Operator.EQ);
        newFilter.setValue("sport");
        VectorSearchRequest req2 = request(normVec(8, 0.3f), 10);
        req2.setFilter(newFilter);
        List<VectorSearchResult> hit = store.search(req2);
        assertEquals(1, hit.size());
        assertEquals("d1", hit.get(0).getId());
    }

    @Test
    public void testSnapshotRoundtripNoPrecisionDegradation(@TempDir Path tempDir) {
        VectorLiteProperties props = new VectorLiteProperties();
        props.getStorage().getSnapshotFile().setBasePath(tempDir.toString());
        SnapshotFileStorage storage = new SnapshotFileStorage(props);

        int dim = 16;
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("rt_store");
        def.setDimension(dim);
        def.setMaxCapacity(100000);
        def.setMetric("COSINE");
        def.setQuantization(QuantizationType.SQ8);

        Random random = new Random(42);
        List<float[]> vectors = new ArrayList<>();
        LocalVectorStore store = new LocalVectorStore(def);
        for (int i = 0; i < 1200; i++) {
            float[] v = randVec(random, dim);
            vectors.add(v);
            store.upsert(new VectorDocument("d" + i, v, "text-" + i, new HashMap<>()));
        }
        storage.saveStore(store);

        // 连续两次快照往返，验证无精度累积衰减
        LocalVectorStore loaded1 = new LocalVectorStore(def);
        storage.loadStore(loaded1);
        assertTrue(loaded1.isSQ8Frozen(), "加载后应直接处于冻结状态");

        storage.saveStore(loaded1);
        LocalVectorStore loaded2 = new LocalVectorStore(def);
        storage.loadStore(loaded2);

        assertEquals(store.getActiveCount(), loaded2.getActiveCount());

        // 用原始向量查询两个代次的 Store，Top-10 结果必须完全一致（若存在 requantize 往返则会发散）
        float[] query = vectors.get(777);
        List<String> topGen0 = ids(store.search(request(query, 10)));
        List<String> topGen2 = ids(loaded2.search(request(query, 10)));
        assertEquals(topGen0, topGen2);
        assertEquals("d777", topGen0.get(0), "应精确召回自身");
    }

    @Test
    public void testEuclideanMetricWorksInSQ8Mode() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("l2_store");
        def.setDimension(4);
        def.setMaxCapacity(100000);
        def.setMetric("EUCLIDEAN");
        def.setQuantization(QuantizationType.SQ8);
        LocalVectorStore store = new LocalVectorStore(def);

        // far 距离 query 更远；near 与 query 完全相同距离为 0
        store.upsert(new VectorDocument("far", new float[]{9f, 9f, 9f, 9f}, "t", new HashMap<>()));
        store.upsert(new VectorDocument("near", new float[]{1f, 1f, 1f, 1f}, "t", new HashMap<>()));

        List<VectorSearchResult> results = store.search(request(new float[]{1f, 1f, 1f, 1f}, 2));
        assertEquals(2, results.size());
        assertEquals("near", results.get(0).getId(), "EUCLIDEAN 下近邻应排第一");
        assertTrue(results.get(0).getScore() < results.get(1).getScore(), "EUCLIDEAN 得分越小越相似，应升序排列");
    }

    @Test
    public void testDotProductMetricWorksInSQ8Mode() {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("dot_store");
        def.setDimension(4);
        def.setMaxCapacity(100000);
        def.setMetric("DOT_PRODUCT");
        def.setQuantization(QuantizationType.SQ8);
        LocalVectorStore store = new LocalVectorStore(def);

        // v1 与 query 方向相同但模长大 10 倍；v2 与 query 方向相同、单位长度
        float[] unit = {0.5f, 0.5f, 0.5f, 0.5f};
        float[] large = {5f, 5f, 5f, 5f};
        store.upsert(new VectorDocument("unit_vec", unit.clone(), "t", new HashMap<>()));
        store.upsert(new VectorDocument("large_vec", large.clone(), "t", new HashMap<>()));

        List<VectorSearchResult> results = store.search(request(unit, 2));
        assertEquals(2, results.size());
        assertEquals("large_vec", results.get(0).getId(),
                "DOT_PRODUCT 下模长更大的同向向量应得分更高（cosine 则会并列）");
        assertTrue(results.get(0).getScore() > results.get(1).getScore() * 5,
                "点积得分应体现约 10 倍的模长差异");
    }

    @Test
    public void testReloadDoesNotKeepResidualData(@TempDir Path tempDir) {
        VectorLiteProperties props = new VectorLiteProperties();
        props.getStorage().getSnapshotFile().setBasePath(tempDir.toString());
        SnapshotFileStorage storage = new SnapshotFileStorage(props);

        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("reload_store");
        def.setDimension(4);

        LocalVectorStore store = new LocalVectorStore(def);
        for (int i = 0; i < 3; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("k", "old_" + i);
            store.upsert(new VectorDocument("saved_" + i, normVec(4, 0.2f * (i + 1)), "t", meta));
        }
        storage.saveStore(store);

        // 刷盘后又写入两条未持久化的文档
        for (int i = 0; i < 2; i++) {
            store.upsert(new VectorDocument("residual_" + i, normVec(4, 0.9f), "t", new HashMap<>()));
        }
        assertEquals(5, store.getActiveCount());

        // reload 后必须只包含快照中的 3 条，且倒排索引中不残留 residual 文档的字段值
        storage.loadStore(store);
        assertEquals(3, store.getActiveCount(), "reload 后不应残留快照之外的旧文档");

        FilterExpression filter = new FilterExpression();
        filter.setField("k");
        filter.setOperator(FilterExpression.Operator.EQ);
        filter.setValue("old_0");
        VectorSearchRequest req = request(normVec(4, 0.2f), 10);
        req.setFilter(filter);
        List<VectorSearchResult> hit = store.search(req);
        assertEquals(1, hit.size());
        assertEquals("saved_0", hit.get(0).getId());
    }

    private List<String> ids(List<VectorSearchResult> results) {
        List<String> ids = new ArrayList<>();
        for (VectorSearchResult r : results) {
            ids.add(r.getId());
        }
        return ids;
    }

    private float[] normVec(int dim, float base) {
        float[] v = new float[dim];
        Arrays.fill(v, base);
        return v;
    }

    private float[] randVec(Random random, int dim) {
        float[] v = new float[dim];
        float norm = 0;
        for (int d = 0; d < dim; d++) {
            v[d] = random.nextFloat() * 2 - 1;
            norm += v[d] * v[d];
        }
        norm = (float) Math.sqrt(norm);
        for (int d = 0; d < dim; d++) {
            v[d] /= norm;
        }
        return v;
    }
}

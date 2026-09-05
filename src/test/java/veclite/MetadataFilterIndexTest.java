package veclite;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.FilterExpression;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 倒排位图前置过滤 (EQ / IN) 与 Compact Payload 测试类。
 */
public class MetadataFilterIndexTest {

    @Test
    @DisplayName("测试一层 AND / OR 复合过滤")
    void testCompoundAndOrFilter() {
        VectorLiteProperties properties = new VectorLiteProperties();
        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, null, properties, null);
        String storeName = "compound_filter_store";
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(storeName);
        definition.setDimension(2);
        definition.setMetric("COSINE");
        definition.setIndexedMetadataFields(Arrays.asList("category", "tenant"));
        client.createStore(storeName, definition);

        upsertCompoundDoc(client, storeName, "a", "tech", "t1");
        upsertCompoundDoc(client, storeName, "b", "tech", "t2");
        upsertCompoundDoc(client, storeName, "c", "news", "t1");

        VectorSearchRequest request = new VectorSearchRequest();
        request.setStoreName(storeName);
        request.setQueryVector(new float[]{1, 0});
        request.setTopK(10);
        request.setFilter(FilterExpression.and(
                FilterExpression.eq("category", "tech"),
                FilterExpression.eq("tenant", "t1")));
        List<VectorSearchResult> andResults = client.searchByVector(request);
        assertEquals(1, andResults.size());
        assertEquals("a", andResults.get(0).getId());

        request.setFilter(FilterExpression.or(
                FilterExpression.eq("category", "news"),
                FilterExpression.eq("tenant", "t2")));
        List<VectorSearchResult> orResults = client.searchByVector(request);
        assertEquals(2, orResults.size());
        assertTrue(orResults.stream().anyMatch(r -> r.getId().equals("b")));
        assertTrue(orResults.stream().anyMatch(r -> r.getId().equals("c")));
    }

    private static void upsertCompoundDoc(VectorEngineClientImpl client, String storeName,
                                          String id, String category, String tenant) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("category", category);
        metadata.put("tenant", tenant);
        client.upsert(storeName, new VectorDocument(id, new float[]{1, 0}, id, metadata));
    }

    @Test
    @DisplayName("测试倒排位图索引 EQ 和 IN 前置过滤及检索准确性")
    void testBitSetFilterEQAndIN() {
        VectorLiteProperties properties = new VectorLiteProperties();
        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, null, properties, null);

        String storeName = "filter_test_store";
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(storeName);
        definition.setDimension(4);
        definition.setMetric("COSINE");
        definition.setIndexedMetadataFields(Arrays.asList("category", "tenant_id"));

        client.createStore(storeName, definition);

        // 插入 100 条文档，设置不同 category 和 tenant_id
        for (int i = 0; i < 100; i++) {
            VectorDocument doc = new VectorDocument();
            doc.setId("doc_" + i);
            doc.setVector(new float[]{1.0f, (float) i / 100.0f, 0.0f, 0.0f});
            doc.setText("文档内容 " + i);

            Map<String, Object> meta = new HashMap<>();
            meta.put("category", i % 2 == 0 ? "tech" : (i % 3 == 0 ? "ai" : "news"));
            meta.put("tenant_id", "tenant_" + (i % 5));
            doc.setMetadata(meta);

            client.upsert(storeName, doc);
        }

        LocalVectorStore store = engine.getStore(storeName);
        Assertions.assertEquals(100, store.getActiveCount());

        // 1. 测试 EQ 前置过滤 (category = 'tech')
        VectorSearchRequest eqRequest = new VectorSearchRequest();
        eqRequest.setStoreName(storeName);
        eqRequest.setQueryVector(new float[]{1.0f, 0.5f, 0.0f, 0.0f});
        eqRequest.setTopK(100);
        eqRequest.setFilter(FilterExpression.eq("category", "tech"));

        List<VectorSearchResult> eqResults = client.searchByVector(eqRequest);
        Assertions.assertFalse(eqResults.isEmpty());
        for (VectorSearchResult res : eqResults) {
            Assertions.assertEquals("tech", res.getMetadata().get("category"));
        }
        System.out.println("✅ EQ 过滤成功，匹配 'tech' 的结果数量: " + eqResults.size());

        // 2. 测试 IN 前置过滤 (category IN ['tech', 'ai'])
        VectorSearchRequest inRequest = new VectorSearchRequest();
        inRequest.setStoreName(storeName);
        inRequest.setQueryVector(new float[]{1.0f, 0.5f, 0.0f, 0.0f});
        inRequest.setTopK(100);
        inRequest.setFilter(FilterExpression.in("category", Arrays.asList("tech", "ai")));

        List<VectorSearchResult> inResults = client.searchByVector(inRequest);
        Assertions.assertFalse(inResults.isEmpty());
        for (VectorSearchResult res : inResults) {
            String cat = (String) res.getMetadata().get("category");
            Assertions.assertTrue("tech".equals(cat) || "ai".equals(cat));
        }
        System.out.println("✅ IN 过滤成功，匹配 IN ['tech', 'ai'] 的结果数量: " + inResults.size());
    }

    @Test
    @DisplayName("测试 GT / LT 范围过滤（走 matchesFilter 降级路径，元数据为数值）")
    void testRangeFilterGTAndLT() {
        VectorLiteProperties properties = new VectorLiteProperties();
        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, null, properties, null);

        String storeName = "range_test_store";
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(storeName);
        definition.setDimension(4);
        definition.setMetric("COSINE");
        definition.setIndexedMetadataFields(Collections.singletonList("score"));

        client.createStore(storeName, definition);

        // 插入 10 条文档，score 0..9
        for (int i = 0; i < 10; i++) {
            VectorDocument doc = new VectorDocument();
            doc.setId("doc_" + i);
            doc.setVector(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            Map<String, Object> meta = new HashMap<>();
            meta.put("score", i);
            doc.setMetadata(meta);
            client.upsert(storeName, doc);
        }

        // GT 5 → 期望 6,7,8,9
        VectorSearchRequest gtReq = new VectorSearchRequest();
        gtReq.setStoreName(storeName);
        gtReq.setQueryVector(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        gtReq.setTopK(100);
        FilterExpression gtFilter = new FilterExpression();
        gtFilter.setField("score");
        gtFilter.setOperator(FilterExpression.Operator.GT);
        gtFilter.setValue(5);
        gtReq.setFilter(gtFilter);
        List<VectorSearchResult> gtResults = client.searchByVector(gtReq);
        Assertions.assertEquals(4, gtResults.size());
        for (VectorSearchResult r : gtResults) {
            int s = ((Number) r.getMetadata().get("score")).intValue();
            Assertions.assertTrue(s > 5, "GT 5 应当 > 5，实际 " + s);
        }
        System.out.println("✅ GT 过滤成功，score>5 命中 " + gtResults.size() + " 条");

        // LT 3 → 期望 0,1,2
        VectorSearchRequest ltReq = new VectorSearchRequest();
        ltReq.setStoreName(storeName);
        ltReq.setQueryVector(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        ltReq.setTopK(100);
        FilterExpression ltFilter = new FilterExpression();
        ltFilter.setField("score");
        ltFilter.setOperator(FilterExpression.Operator.LT);
        ltFilter.setValue(3);
        ltReq.setFilter(ltFilter);
        List<VectorSearchResult> ltResults = client.searchByVector(ltReq);
        Assertions.assertEquals(3, ltResults.size());
        for (VectorSearchResult r : ltResults) {
            int s = ((Number) r.getMetadata().get("score")).intValue();
            Assertions.assertTrue(s < 3, "LT 3 应当 < 3，实际 " + s);
        }
        System.out.println("✅ LT 过滤成功，score<3 命中 " + ltResults.size() + " 条");
    }

    @Test
    @DisplayName("回归：IN 列表对字符串值不再自动转 Number/Boolean（修复前端 coerce 误转）")
    void testInStringValuesNotCoerced() {
        VectorLiteProperties properties = new VectorLiteProperties();
        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, null, properties, null);

        String storeName = "in_string_test_store";
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(storeName);
        definition.setDimension(4);
        definition.setMetric("COSINE");
        definition.setIndexedMetadataFields(Collections.singletonList("tag"));

        client.createStore(storeName, definition);

        // 元数据是字符串
        for (String tag : Arrays.asList("apple", "banana", "cherry", "123", "true")) {
            VectorDocument doc = new VectorDocument();
            doc.setId("doc_" + tag);
            doc.setVector(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            Map<String, Object> meta = new HashMap<>();
            meta.put("tag", tag);  // 字符串
            doc.setMetadata(meta);
            client.upsert(storeName, doc);
        }

        // 用字符串值查，必须能命中
        VectorSearchRequest req = new VectorSearchRequest();
        req.setStoreName(storeName);
        req.setQueryVector(new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        req.setTopK(100);
        req.setFilter(FilterExpression.in("tag", Arrays.asList("apple", "banana", "123", "true")));
        List<VectorSearchResult> results = client.searchByVector(req);
        Assertions.assertEquals(4, results.size(), "IN 字符串值应全部命中");
        Set<String> got = new HashSet<>();
        for (VectorSearchResult r : results) got.add((String) r.getMetadata().get("tag"));
        Assertions.assertTrue(got.contains("apple"));
        Assertions.assertTrue(got.contains("banana"));
        Assertions.assertTrue(got.contains("123"));
        Assertions.assertTrue(got.contains("true"));
        System.out.println("✅ IN 字符串值未被误转，命中 " + results.size() + " 条");
    }
}

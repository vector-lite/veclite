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

/**
 * 倒排位图前置过滤 (EQ / IN) 与 Compact Payload 测试类。
 */
public class MetadataFilterIndexTest {

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
}

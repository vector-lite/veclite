package veclite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.QuantizationType;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import veclite.quantization.SQ8Quantizer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class SQ8PrecomputationTest {

    @Test
    @DisplayName("验证 SQ8Precomputation 数学代数展开与传统 calculateCosine 的绝对相等性 (epsilon <= 1e-5f)")
    void testSQ8PrecomputationMathIdentity() {
        int dim = 512;
        float min = -0.5f;
        float max = 0.5f;

        Random random = new Random(42);
        float[] query = new float[dim];
        for (int i = 0; i < dim; i++) {
            query[i] = random.nextFloat() - 0.5f;
        }

        SQ8Quantizer.SQ8QueryPrecomputation precomp = SQ8Quantizer.precompute(query, min, max);

        for (int t = 0; t < 100; t++) {
            float[] targetFloat = new float[dim];
            byte[] targetBytes = new byte[dim];
            for (int i = 0; i < dim; i++) {
                targetFloat[i] = (random.nextFloat() - 0.5f);
            }
            SQ8Quantizer.quantize(targetFloat, min, max, targetBytes);

            float legacyScore = SQ8Quantizer.calculateCosine(query, targetBytes, min, max);
            float precompScore = SQ8Quantizer.calculateScorePrecomputed(precomp, targetBytes, 0, "COSINE");

            assertEquals(legacyScore, precompScore, 1e-5f, "Precomputed score and legacy score must match within epsilon 1e-5f");

            ByteBuffer directBuf = ByteBuffer.allocateDirect(dim);
            directBuf.put(targetBytes);
            float directScore = SQ8Quantizer.calculateScorePrecomputed(precomp, directBuf, 0, "COSINE");
            assertEquals(legacyScore, directScore, 1e-5f, "DirectByteBuffer score and legacy score must match within epsilon 1e-5f");
        }
    }

    @Test
    @DisplayName("验证预计算开启前后 VectorEngineClient 检索 Top-K 结果与得分 100% 保持一致")
    void testVectorEngineSearchConsistency() throws Exception {
        int dim = 512;
        int count = 1000;
        String storeName = "test_precomp_consistency";

        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getSearcher().getPrecomputation().setEnabled(true);

        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, null, properties);

        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(storeName);
        definition.setDimension(dim);
        definition.setMaxCapacity(count + 100);
        definition.setMetric("COSINE");
        definition.setQuantization(QuantizationType.SQ8);
        client.createStore(storeName, definition);

        Random random = new Random(123);
        float[] queryVector = new float[dim];
        for (int d = 0; d < dim; d++) {
            queryVector[d] = random.nextFloat();
        }

        for (int i = 0; i < count; i++) {
            float[] vec = new float[dim];
            for (int d = 0; d < dim; d++) {
                vec[d] = random.nextFloat();
            }
            VectorDocument doc = new VectorDocument();
            doc.setId("doc_" + i);
            doc.setText("Sample text " + i);
            doc.setVector(vec);
            client.upsert(storeName, doc);
        }

        // 1. 开启预计算查询
        VectorSearchRequest request = new VectorSearchRequest();
        request.setStoreName(storeName);
        request.setQueryVector(queryVector);
        request.setTopK(10);
        List<VectorSearchResult> precompResults = client.searchByVector(request);

        // 2. 关闭预计算查询
        properties.getSearcher().getPrecomputation().setEnabled(false);
        List<VectorSearchResult> legacyResults = client.searchByVector(request);

        assertEquals(precompResults.size(), legacyResults.size());
        for (int i = 0; i < precompResults.size(); i++) {
            assertEquals(legacyResults.get(i).getId(), precompResults.get(i).getId());
            assertEquals(legacyResults.get(i).getScore(), precompResults.get(i).getScore(), 1e-5f);
        }
    }
}

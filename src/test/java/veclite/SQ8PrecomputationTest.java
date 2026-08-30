package veclite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.QuantizationType;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import veclite.quantization.SQ8Quantizer;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class SQ8PrecomputationTest {

    @Test
    @DisplayName("验证 SQ8 零拷贝计算与反量化后计算的余弦相似度完全一致 (epsilon <= 1e-5f)")
    void testSQ8CosineMathIdentity() {
        int dim = 512;
        Random random = new Random(42);

        float[] minPerDim = new float[dim];
        float[] maxPerDim = new float[dim];
        float[] scalePerDim = new float[dim];
        for (int i = 0; i < dim; i++) {
            minPerDim[i] = -0.5f + random.nextFloat() * 0.1f;
            maxPerDim[i] = 0.5f + random.nextFloat() * 0.1f;
            scalePerDim[i] = (maxPerDim[i] - minPerDim[i]) / 255.0f;
        }

        float[] query = new float[dim];
        float queryNormSq = 0.0f;
        for (int i = 0; i < dim; i++) {
            query[i] = random.nextFloat() - 0.5f;
            queryNormSq += query[i] * query[i];
        }
        float queryNormInv = queryNormSq > 0.0f ? 1.0f / (float) Math.sqrt(queryNormSq) : 0.0f;

        for (int t = 0; t < 100; t++) {
            float[] targetFloat = new float[dim];
            byte[] targetBytes = new byte[dim];
            for (int i = 0; i < dim; i++) {
                targetFloat[i] = (random.nextFloat() - 0.5f);
            }
            SQ8Quantizer.quantize(targetFloat, minPerDim, scalePerDim, targetBytes);

            float[] dequantized = new float[dim];
            SQ8Quantizer.dequantize(targetBytes, minPerDim, scalePerDim, dequantized);

            float targetNorm = SQ8Quantizer.l2Norm(dequantized);
            float targetNormInv = targetNorm > 0.0f ? 1.0f / targetNorm : 0.0f;

            float directScore = SQ8Quantizer.calculateCosineWithNorms(query, targetBytes, 0, minPerDim, scalePerDim, queryNormInv, targetNormInv);

            // 传统反量化计算余弦
            float dot = 0.0f;
            for (int i = 0; i < dim; i++) {
                dot += query[i] * dequantized[i];
            }
            float expectedScore = dot * queryNormInv * targetNormInv;

            assertEquals(expectedScore, directScore, 1e-5f, "Direct score and dequantized score must match within epsilon 1e-5f");
        }
    }

    @Test
    @DisplayName("验证 SQ8 量化存储的检索正确性与一致性")
    void testVectorEngineSQ8SearchConsistency() throws Exception {
        int dim = 128;
        int count = 200;
        String storeName = "test_sq8_consistency";

        VectorLiteProperties properties = new VectorLiteProperties();
        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, null, properties, null);

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

        VectorSearchRequest request = new VectorSearchRequest();
        request.setStoreName(storeName);
        request.setQueryVector(queryVector);
        request.setTopK(10);
        List<VectorSearchResult> results = client.searchByVector(request);

        assertEquals(10, results.size());
        assertNotNull(results.get(0).getId());
        assertTrue(results.get(0).getScore() > 0);
    }
}

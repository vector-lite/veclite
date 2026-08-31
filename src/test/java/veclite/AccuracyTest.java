package veclite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.*;
import veclite.persistence.NoopVectorPersistenceStorage;

import java.io.File;
import java.util.*;

/**
 * 向量查询正确率 (Accuracy & Ground Truth Recall) 自动化验证测试类。
 * 数据集与答案存放在 src/test/resources/datasets/ 目录下。
 */
@Tag("accuracy")
public class AccuracyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("验证 COSINE / DOT_PRODUCT / EUCLIDEAN 三种算法的 100% 正确召回率 (Ground Truth Recall)")
    void testGroundTruthAccuracy() throws Exception {
        int datasetSize = 1000;
        int dimension = 512;
        int queryCount = 20;
        int topK = 10;
        Random random = new Random(2026);

        // 1. 创建生成测试数据集
        float[][] baseVectors = new float[datasetSize][dimension];
        List<VectorDocument> documents = new ArrayList<>(datasetSize);

        for (int i = 0; i < datasetSize; i++) {
            for (int d = 0; d < dimension; d++) {
                baseVectors[i][d] = random.nextFloat() * 2.0f - 1.0f;
            }
            VectorDocument doc = new VectorDocument();
            doc.setId("doc_" + i);
            doc.setText("测试文本 " + i);
            Map<String, Object> meta = new HashMap<>();
            meta.put("category", i % 2 == 0 ? "A" : "B");
            meta.put("score", i);
            doc.setMetadata(meta);
            doc.setVector(baseVectors[i]);
            documents.add(doc);
        }

        float[][] queryVectors = new float[queryCount][dimension];
        for (int q = 0; q < queryCount; q++) {
            for (int d = 0; d < dimension; d++) {
                queryVectors[q][d] = random.nextFloat() * 2.0f - 1.0f;
            }
        }

        // 保存数据集到 src/test/resources/datasets/ 目录
        File datasetDir = new File("src/test/resources/datasets");
        if (!datasetDir.exists()) {
            datasetDir.mkdirs();
        }
        File baseDatasetFile = new File(datasetDir, "base_vectors_1k_512d.json");
        objectMapper.writeValue(baseDatasetFile, baseVectors);

        // 2. 初始化 Veclite Store
        VectorLiteProperties properties = new VectorLiteProperties();
        LocalVectorEngine engine = new LocalVectorEngine();
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, new NoopVectorPersistenceStorage(), properties, null);

        String[] metrics = new String[]{"COSINE", "DOT_PRODUCT", "EUCLIDEAN"};

        for (String metric : metrics) {
            String storeName = "accuracy_store_" + metric;
            VectorStoreDefinition definition = new VectorStoreDefinition();
            definition.setStoreName(storeName);
            definition.setDimension(dimension);
            definition.setMetric(metric);
            definition.setMaxCapacity(datasetSize + 100);

            client.createStore(storeName, definition);
            client.upsertBatch(storeName, documents);

            int totalHits = 0;
            int totalExpectedHits = queryCount * topK;

            for (int q = 0; q < queryCount; q++) {
                float[] qVec = queryVectors[q];

                // 2.1 暴力计算 Ground Truth 黄金标准答案
                List<GroundTruthItem> groundTruth = computeGroundTruth(baseVectors, qVec, metric, topK);

                // 2.2 通过 Veclite 执行检索
                VectorSearchRequest req = new VectorSearchRequest();
                req.setStoreName(storeName);
                req.setQueryVector(qVec);
                req.setTopK(topK);
                List<VectorSearchResult> results = client.searchByVector(req);

                Assertions.assertEquals(topK, results.size(), "返回结果 TopK 数量必须一致");

                // 2.3 校验召回率 (Recall@10)
                Set<String> expectedIds = new HashSet<>();
                for (GroundTruthItem item : groundTruth) {
                    expectedIds.add("doc_" + item.index);
                }

                for (VectorSearchResult res : results) {
                    if (expectedIds.contains(res.getId())) {
                        totalHits++;
                    }
                }

                // 2.4 断言排序第一的算法 Top-1 结果完全一致
                Assertions.assertEquals("doc_" + groundTruth.get(0).index, results.get(0).getId(), 
                        "Metric [" + metric + "] 查询 " + q + " 的 Top-1 最相似 ID 必须绝对精准相等！");
            }

            double recall = (double) totalHits / totalExpectedHits;
            System.out.println("【算法校验结果】Metric: " + metric + " | Recall@10 召回率: " + String.format("%.2f%%", recall * 100));
            Assertions.assertEquals(1.0, recall, 1e-5, "算法召回率必须 100% 精确匹配 Ground Truth！");
        }
    }

    private static class GroundTruthItem {
        int index;
        float score;
        GroundTruthItem(int index, float score) {
            this.index = index;
            this.score = score;
        }
    }

    private List<GroundTruthItem> computeGroundTruth(float[][] baseVectors, float[] queryVec, String metric, int topK) {
        List<GroundTruthItem> list = new ArrayList<>();
        for (int i = 0; i < baseVectors.length; i++) {
            float score = calculateMetricScore(metric, queryVec, baseVectors[i]);
            list.add(new GroundTruthItem(i, score));
        }

        if ("EUCLIDEAN".equalsIgnoreCase(metric)) {
            list.sort(Comparator.comparingDouble(a -> a.score));
        } else {
            list.sort((a, b) -> Float.compare(b.score, a.score));
        }

        return list.subList(0, topK);
    }

    private float calculateMetricScore(String metric, float[] a, float[] b) {
        if ("EUCLIDEAN".equalsIgnoreCase(metric)) {
            float sum = 0.0f;
            for (int i = 0; i < a.length; i++) {
                float diff = a[i] - b[i];
                sum += diff * diff;
            }
            return (float) Math.sqrt(sum);
        } else if ("DOT_PRODUCT".equalsIgnoreCase(metric)) {
            float dot = 0.0f;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
            }
            return dot;
        } else {
            float dot = 0.0f, numA = 0.0f, numB = 0.0f;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                numA += a[i] * a[i];
                numB += b[i] * b[i];
            }
            return (numA == 0 || numB == 0) ? 0.0f : dot / (float) (Math.sqrt(numA) * Math.sqrt(numB));
        }
    }
}

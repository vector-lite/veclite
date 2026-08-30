package veclite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.FilterExpression;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import veclite.persistence.NoopVectorPersistenceStorage;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟 1核1G (1 CPU Core, 1GB RAM) 极限硬件瓶颈压测类。
 */
@Tag("stress")
public class LowResourceStressTest {

    @Test
    @DisplayName("模拟 1核1G 受限资源下的 QPS, P50/P99 延迟与前置 Filter 性能压测")
    void testLowResourcePerformance() throws Exception {
        int dimension = 512;
        int topK = 10;
        int queryCount = 2000;
        Random random = new Random(42);

        System.out.println("==================================================");
        System.out.println("开始 1核1G 极限性能压测：评估不同向量规模下的 QPS 与 P99 延迟...");
        System.out.println("==================================================");

        int[] datasetSizes = new int[]{10_000, 50_000, 100_000};
        int[] concurrencyLevels = new int[]{1, 4, 8};

        for (int datasetSize : datasetSizes) {
            System.out.println("\n--------------------------------------------------");
            System.out.println("【数据规模】: " + datasetSize + " 条 512 维向量");
            System.out.println("--------------------------------------------------");

            // 初始化 Store 并填充数据
            VectorLiteProperties properties = new VectorLiteProperties();
            LocalVectorEngine engine = new LocalVectorEngine();
            VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, new NoopVectorPersistenceStorage(), properties, null);

            String storeName = "stress_store_" + datasetSize;
            VectorStoreDefinition definition = new VectorStoreDefinition();
            definition.setStoreName(storeName);
            definition.setDimension(dimension);
            definition.setMetric("COSINE");
            definition.setMaxCapacity(datasetSize + 1000);

            client.createStore(storeName, definition);

            List<VectorDocument> docs = new ArrayList<>(datasetSize);
            for (int i = 0; i < datasetSize; i++) {
                float[] vec = new float[dimension];
                for (int d = 0; d < dimension; d++) {
                    vec[d] = random.nextFloat() * 2.0f - 1.0f;
                }
                VectorDocument doc = new VectorDocument();
                doc.setId("doc_" + i);
                Map<String, Object> meta = new HashMap<>();
                meta.put("category", i % 10 == 0 ? "FILTERED" : "NORMAL"); // 10% 匹配概率
                doc.setMetadata(meta);
                doc.setVector(vec);
                docs.add(doc);
            }
            client.upsertBatch(storeName, docs);

            // 预生成查询向量集
            float[][] queryVectors = new float[100][dimension];
            for (int q = 0; q < 100; q++) {
                for (int d = 0; d < dimension; d++) {
                    queryVectors[q][d] = random.nextFloat() * 2.0f - 1.0f;
                }
            }

            // 1. 无 Filter 条件全量 Search 压测
            for (int threads : concurrencyLevels) {
                runBenchmark(client, storeName, queryVectors, queryCount, threads, topK, null, "无 Filter 全量 Search");
            }

            // 2. 带 Metadata Filter (90% 裁剪率) 前置过滤 Search 压测
            FilterExpression filter = FilterExpression.eq("category", "FILTERED");
            runBenchmark(client, storeName, queryVectors, queryCount, 4, topK, filter, "带 Metadata Filter (90% 前置裁剪)");

            // 清理垃圾
            engine.dropStore(storeName);
            System.gc();
        }
    }

    private void runBenchmark(VectorEngineClientImpl client, String storeName, float[][] queryVectors,
                              int totalQueries, int threads, int topK, FilterExpression filter, String tag) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalQueries);
        long[] latenciesNs = new long[totalQueries];
        AtomicInteger counter = new AtomicInteger(0);

        long startTestTime = System.currentTimeMillis();

        for (int i = 0; i < totalQueries; i++) {
            final int index = i;
            final float[] queryVector = queryVectors[index % queryVectors.length];
            executor.submit(() -> {
                try {
                    VectorSearchRequest req = new VectorSearchRequest();
                    req.setStoreName(storeName);
                    req.setQueryVector(queryVector);
                    req.setTopK(topK);
                    req.setFilter(filter);

                    long start = System.nanoTime();
                    List<VectorSearchResult> results = client.searchByVector(req);
                    long duration = System.nanoTime() - start;

                    int pos = counter.getAndIncrement();
                    if (pos < totalQueries) {
                        latenciesNs[pos] = duration;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long endTestTime = System.currentTimeMillis();
        executor.shutdown();

        long totalTimeMs = endTestTime - startTestTime;
        double qps = (totalQueries * 1000.0) / totalTimeMs;

        Arrays.sort(latenciesNs, 0, totalQueries);
        double p50Ms = latenciesNs[(int) (totalQueries * 0.50)] / 1_000_000.0;
        double p95Ms = latenciesNs[(int) (totalQueries * 0.95)] / 1_000_000.0;
        double p99Ms = latenciesNs[(int) (totalQueries * 0.99)] / 1_000_000.0;

        System.out.println(String.format("  [%s | %d 并发线程] QPS: %.2f ops/s | Latency P50: %.2f ms | P95: %.2f ms | P99: %.2f ms",
                tag, threads, qps, p50Ms, p95Ms, p99Ms));
    }
}

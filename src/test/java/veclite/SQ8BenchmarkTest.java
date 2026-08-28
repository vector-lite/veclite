package veclite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.QuantizationType;
import veclite.model.StorageType;
import veclite.model.VectorDocument;
import veclite.persistence.SnapshotFileStorage;
import veclite.persistence.VectorPersistenceStorage;

import java.io.File;
import java.util.*;

/**
 * SQ8 量化模式下的多规模向量库性能基准测试。
 * <p>
 * 测试条件：
 * - 开启 SQ8 量化存储（QuantizationType.SQ8 + rerank = true）
 * - 不开启多线程并行搜索（parallel.enabled = false）
 * - 不开启堆外内存（off-heap = false）
 * - 向量维度：512 维
 * - 距离度量：余弦相似度 (COSINE)
 */
@Tag("benchmark")
public class SQ8BenchmarkTest {

    /**
     * 测试 5 个不同规模（10w/20w/30w/50w/100w）512 维向量库的 SQ8 量化性能指标。
     */
    @Test
    @DisplayName("SQ8量化模式 - 5个不同规模向量库（10w/20w/30w/50w/100w）性能基准测试")
    void testSQ8BenchmarkMultipleScales() {
        int dimension = 512;
        int[] storeSizes = {100_000, 200_000, 300_000, 500_000, 1_000_000};
        String[] storeNames = {"store_10w", "store_20w", "store_30w", "store_50w", "store_100w"};

        System.out.println("====================================================================");
        System.out.println("  纯 SQ8 量化模式 - 多规模向量库性能基准测试 (无 Float32 开销)");
        System.out.println("  向量维度: " + dimension + "  |  度量: COSINE  |  量化: SQ8");
        System.out.println("  并行搜索: 关闭  |  堆外内存: 关闭");
        System.out.println("====================================================================\n");

        // 配置 Properties: 禁用并行搜索, 设置持久化路径
        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().setType(StorageType.SNAPSHOT_FILE);
        String resourcePath = new File("src/main/resources/vec").getAbsolutePath();
        properties.getStorage().getSnapshotFile().setBasePath(resourcePath);
        properties.getSearcher().getParallel().setEnabled(false);

        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorPersistenceStorage storage = new SnapshotFileStorage(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, storage, properties);

        runGC();
        long baselineMemory = getUsedMemoryMB();
        System.out.println("基准 JVM 堆内存: " + baselineMemory + " MB\n");

        // 收集每个 Store 的测试结果
        long[][] results = new long[storeSizes.length][8];
        // [0] = vectorCount, [1] = totalBuildTimeMs, [2] = upsertTotalNanos,
        // [3] = netMemoryMB, [4] = sq8DataSizeKB, [5] = flushTimeMs,
        // [6] = gcStallCount, [7] = currentMemoryMB

        Random random = new Random(42);

        for (int s = 0; s < storeSizes.length; s++) {
            int vectorCount = storeSizes[s];
            String storeName = storeNames[s];

            System.out.println("--------------------------------------------------------------");
            System.out.printf("[Store %d/5] %s — 创建 %,d 条 %d 维向量 (SQ8量化)...%n",
                    s + 1, storeName, vectorCount, dimension);
            System.out.println("--------------------------------------------------------------");

            // 创建 Store Definition (纯 SQ8 量化，无 Float32 额外内存分配)
            VectorStoreDefinition definition = new VectorStoreDefinition();
            definition.setStoreName(storeName);
            definition.setDimension(dimension);
            definition.setMaxCapacity(vectorCount + 1000);
            definition.setMetric("COSINE");
            definition.setQuantization(QuantizationType.SQ8);

            client.createStore(storeName, definition);

            long storeStartTime = System.currentTimeMillis();
            long totalUpsertNanos = 0;
            int gcStallCount = 0;
            long lastBatchEndTime = System.currentTimeMillis();

            for (int i = 0; i < vectorCount; i++) {
                // 生成随机向量与文档
                float[] vector = new float[dimension];
                for (int d = 0; d < dimension; d++) {
                    vector[d] = random.nextFloat();
                }

                VectorDocument doc = new VectorDocument();
                doc.setId("doc_" + storeName + "_" + i);
                doc.setText("测试文本 " + i);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("category", "benchmark");
                metadata.put("index", i);
                doc.setMetadata(metadata);
                doc.setVector(vector);

                // 测量 upsert 耗时
                long upsertStart = System.nanoTime();
                client.upsert(storeName, doc);
                long upsertEnd = System.nanoTime();
                totalUpsertNanos += (upsertEnd - upsertStart);

                // 每 5 万条检测 GC 停顿
                if ((i + 1) % 50_000 == 0) {
                    long now = System.currentTimeMillis();
                    long batchGapMs = now - lastBatchEndTime;
                    long expectedMaxMs = 50_000L * 10; // 保守估计每条 10ms 以内
                    if (batchGapMs > expectedMaxMs) {
                        gcStallCount++;
                        System.out.printf("  ⚠ 潜在 GC 停顿: 第 %,d 条处，批次间隔 %,d ms%n", i + 1, batchGapMs);
                    }

                    runGC();
                    long currentMem = getUsedMemoryMB();
                    double upsertMs = totalUpsertNanos / 1_000_000.0;
                    double ops = (i + 1) / (upsertMs / 1000.0);
                    System.out.printf("  进度: %,d / %,d  |  内存: %,d MB  |  瞬时吞吐: %,.0f ops/s%n",
                            i + 1, vectorCount, currentMem, ops);
                    lastBatchEndTime = System.currentTimeMillis();
                }
            }

            long storeBuildTimeMs = System.currentTimeMillis() - storeStartTime;
            double upsertTotalMs = totalUpsertNanos / 1_000_000.0;
            double avgUpsertUs = (totalUpsertNanos / 1000.0) / vectorCount;
            double throughput = vectorCount / (upsertTotalMs / 1000.0);

            runGC();
            long currentMemory = getUsedMemoryMB();
            long netMemory = currentMemory - baselineMemory;

            // 获取 SQ8 数据大小
            LocalVectorStore store = engine.getStore(storeName);
            long sq8Bytes = store.getSQ8DataSizeBytes();
            long sq8KB = sq8Bytes / 1024;

            System.out.printf("%n[%s] 构建完成:%n", storeName);
            System.out.printf("  总构建耗时: %,d ms (%.2f 秒)%n", storeBuildTimeMs, storeBuildTimeMs / 1000.0);
            System.out.printf("  纯 Upsert 耗时: %.2f ms%n", upsertTotalMs);
            System.out.printf("  平均单条 Upsert: %.3f μs%n", avgUpsertUs);
            System.out.printf("  单线程吞吐量: %,.0f ops/sec%n", throughput);
            System.out.printf("  净增堆内存: %,d MB (%.2f GB)%n", netMemory, netMemory / 1024.0);
            System.out.printf("  SQ8 Byte 缓冲区: %,d KB (%.2f MB)%n", sq8KB, sq8KB / 1024.0);
            System.out.printf("  SQ8 量化已启用: %s%n", store.isSQ8Enabled());
            System.out.printf("  GC 停顿检测次数: %d%n", gcStallCount);

            // 快照刷盘
            System.out.printf("  正在刷盘保存快照...%n");
            long flushStart = System.currentTimeMillis();
            client.refresh(storeName);
            long flushTimeMs = System.currentTimeMillis() - flushStart;
            System.out.printf("  快照刷盘耗时: %,d ms (%.2f 秒)%n%n", flushTimeMs, flushTimeMs / 1000.0);

            // 记录结果
            results[s][0] = vectorCount;
            results[s][1] = storeBuildTimeMs;
            results[s][2] = totalUpsertNanos;
            results[s][3] = netMemory;
            results[s][4] = sq8KB;
            results[s][5] = flushTimeMs;
            results[s][6] = gcStallCount;
            results[s][7] = currentMemory;
        }

        // 汇总报告
        System.out.println("\n====================================================================");
        System.out.println("  SQ8 量化模式 — 多规模基准测试汇总表");
        System.out.println("====================================================================");
        System.out.printf("%-12s | %-10s | %-14s | %-16s | %-10s | %-12s | %-10s | %-8s%n",
                "Store", "向量数", "构建耗时(s)", "吞吐量(ops/s)", "净内存(MB)", "SQ8数据(KB)", "刷盘(ms)", "GC停顿");
        System.out.println("-------------|------------|----------------|------------------|------------|--------------|------------|--------");

        for (int s = 0; s < storeSizes.length; s++) {
            long vectorCount = results[s][0];
            double buildTimeSec = results[s][1] / 1000.0;
            double upsertMs = results[s][2] / 1_000_000.0;
            double throughput = vectorCount / (upsertMs / 1000.0);
            long netMem = results[s][3];
            long sq8KB = results[s][4];
            long flushMs = results[s][5];
            long gcStalls = results[s][6];

            System.out.printf("%-12s | %,10d | %14.2f | %,16.0f | %,10d | %,12d | %,10d | %8d%n",
                    storeNames[s], vectorCount, buildTimeSec, throughput, netMem, sq8KB, flushMs, gcStalls);
        }

        // 总内存汇总
        runGC();
        long finalMemory = getUsedMemoryMB();
        long totalNet = finalMemory - baselineMemory;
        long totalVectors = 0;
        for (int sz : storeSizes) totalVectors += sz;
        System.out.printf("%n总向量数: %,d | 最终堆内存: %,d MB (净增 %,d MB / %.2f GB)%n",
                totalVectors, finalMemory, totalNet, totalNet / 1024.0);
        System.out.println("====================================================================");
    }

    private void runGC() {
        System.gc();
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}
        System.gc();
    }

    private long getUsedMemoryMB() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }
}

package veclite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.PayloadMode;
import veclite.model.QuantizationType;
import veclite.model.StorageType;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 以单核、1 GB 预算验证推荐的 SQ8 + 预计算 + 堆外 + MMap 部署形态。
 */
class V24ResourcePerformanceBenchmarkTest {

    private static final int DIMENSION = 512;
    private static final int[] VECTOR_COUNTS = {10_000, 50_000, 100_000, 200_000, 500_000};
    private static final int WARMUP_QUERIES = 8;
    private static final int MEASURED_QUERIES = 15;

    @Test
    @DisplayName("V2.4: 单核 1GB 资源预算下的容量与检索性能矩阵")
    void benchmarkCapacityAndSearchUnderOneCoreOneGbBudget(@TempDir Path tempDir) throws Exception {
        if (Boolean.getBoolean("veclite.benchmark.reportOnly")) {
            writeReport(readResults());
            return;
        }
        int vectorCount = selectedVectorCount();
        BenchmarkResult result = runScenario(vectorCount, tempDir.resolve("store-" + vectorCount));
        writeResult(result);
    }

    private BenchmarkResult runScenario(int vectorCount, Path storePath) throws Exception {
        forceGc();
        long heapBefore = usedHeapBytes();
        long directBefore = usedDirectBytes();

        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().setType(StorageType.SNAPSHOT_FILE);
        properties.getStorage().getSnapshotFile().setBasePath(storePath.toString());
        properties.getStorage().getOffHeap().setEnabled(true);
        properties.getStorage().getPayload().setMode(PayloadMode.MMAP);
        properties.getSearcher().getParallel().setEnabled(false);
        properties.getSearcher().getPrecomputation().setEnabled(true);

        String storeName = "benchmark";
        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, null, properties);
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(storeName);
        definition.setDimension(DIMENSION);
        definition.setMaxCapacity(vectorCount);
        definition.setMetric("COSINE");
        definition.setQuantization(QuantizationType.SQ8);
        definition.setIndexedMetadataFields(List.of("category"));
        client.createStore(storeName, definition);

        Random random = new Random(10_000L + vectorCount);
        long insertStart = System.nanoTime();
        for (int i = 0; i < vectorCount; i++) {
            client.upsert(storeName, new VectorDocument(
                    "doc-" + i,
                    nextVector(random),
                    "benchmark payload " + i,
                    Map.of("category", (i & 1) == 0 ? "even" : "odd")
            ));
        }
        double insertQps = vectorCount / elapsedSeconds(insertStart);

        LocalVectorStore store = engine.getStore(storeName);
        forceGc();
        long heapBytes = Math.max(0, usedHeapBytes() - heapBefore);
        long directBytes = Math.max(0, usedDirectBytes() - directBefore);
        long diskBytes = directorySize(storePath);

        List<float[]> queries = new ArrayList<>();
        for (int i = 0; i < WARMUP_QUERIES + MEASURED_QUERIES; i++) {
            queries.add(nextVector(random));
        }
        for (int i = 0; i < WARMUP_QUERIES; i++) {
            search(client, storeName, queries.get(i));
        }

        List<Double> latenciesMs = new ArrayList<>();
        for (int i = 0; i < MEASURED_QUERIES; i++) {
            long start = System.nanoTime();
            List<VectorSearchResult> results = search(client, storeName, queries.get(WARMUP_QUERIES + i));
            latenciesMs.add(elapsedMillis(start));
            assertEquals(10, results.size(), "每次检索都应返回 Top-10");
        }
        latenciesMs.sort(Comparator.naturalOrder());
        double medianMs = percentile(latenciesMs, 0.50);
        double p95Ms = percentile(latenciesMs, 0.95);

        BenchmarkResult result = new BenchmarkResult(
                vectorCount,
                insertQps,
                medianMs,
                p95Ms,
                1_000.0 / medianMs,
                heapBytes,
                directBytes,
                store.getSQ8DataSizeBytes(),
                diskBytes
        );
        System.out.printf(Locale.ROOT,
                "%,d vectors: median=%.2f ms, p95=%.2f ms, QPS=%.2f, heap=%.2f MB, direct=%.2f MB%n",
                vectorCount, medianMs, p95Ms, result.qps, toMiB(heapBytes), toMiB(directBytes));
        return result;
    }

    private List<VectorSearchResult> search(VectorEngineClientImpl client, String storeName, float[] query) {
        VectorSearchRequest request = new VectorSearchRequest();
        request.setStoreName(storeName);
        request.setQueryVector(query);
        request.setTopK(10);
        List<VectorSearchResult> results = client.searchByVector(request);
        assertFalse(results.isEmpty());
        return results;
    }

    private float[] nextVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }

    private int selectedVectorCount() {
        String configuredScale = System.getProperty("veclite.benchmark.scale", "");
        if (configuredScale.isBlank()) {
            throw new IllegalArgumentException("必须通过 -PbenchmarkScale 指定压测规模");
        }
        int vectorCount = Integer.parseInt(configuredScale);
        for (int allowedCount : VECTOR_COUNTS) {
            if (allowedCount == vectorCount) {
                return vectorCount;
            }
        }
        throw new IllegalArgumentException("不支持的压测规模: " + vectorCount);
    }

    private void writeResult(BenchmarkResult result) throws IOException {
        Path resultDir = Path.of("build/benchmarks/v2.4");
        Files.createDirectories(resultDir);
        Path resultFile = resultDir.resolve(result.vectorCount + ".tsv");
        String line = String.format(Locale.ROOT, "%d\t%.6f\t%.6f\t%.6f\t%.6f\t%d\t%d\t%d\t%d%n",
                result.vectorCount, result.insertQps, result.medianMs, result.p95Ms, result.qps,
                result.heapBytes, result.directBytes, result.sq8Bytes, result.diskBytes);
        Files.writeString(resultFile, line, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private List<BenchmarkResult> readResults() throws IOException {
        List<BenchmarkResult> results = new ArrayList<>();
        Path resultDir = Path.of("build/benchmarks/v2.4");
        for (int vectorCount : VECTOR_COUNTS) {
            Path resultFile = resultDir.resolve(vectorCount + ".tsv");
            if (!Files.exists(resultFile)) {
                results.add(BenchmarkResult.failed(vectorCount, readFailureMessage(vectorCount)));
                continue;
            }
            String[] values = Files.readString(resultFile).trim().split("\\t");
            results.add(new BenchmarkResult(
                    Integer.parseInt(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]),
                    Double.parseDouble(values[3]), Double.parseDouble(values[4]), Long.parseLong(values[5]),
                    Long.parseLong(values[6]), Long.parseLong(values[7]), Long.parseLong(values[8])
            ));
        }
        return results;
    }

    private String readFailureMessage(int vectorCount) throws IOException {
        String configuredFailure = System.getProperty("veclite.benchmark.failure", "");
        if (!configuredFailure.isBlank()) {
            return configuredFailure;
        }
        Path testResults = Path.of("build/test-results/v24ResourceBenchmark");
        try (Stream<Path> files = Files.list(testResults)) {
            boolean directMemoryOom = files
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .map(this::readFile)
                    .anyMatch(content -> content.contains("OutOfMemoryError: Cannot reserve")
                            && content.contains("OffHeapSQ8Buffer.ensureCapacity"));
            if (directMemoryOom) {
                return vectorCount + " 条写入期间 Direct Buffer 扩容触发 OOM";
            }
        }
        throw new IllegalStateException("缺少压测结果，且未找到失败证据: " + vectorCount);
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取测试结果: " + path, exception);
        }
    }

    private void writeReport(List<BenchmarkResult> results) throws IOException {
        Path report = Path.of("src/main/resources/report/v2.4/veclite_v2.4_comprehensive_benchmark_report.md");
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Veclite V2.4 单核 1GB 容量与性能压测报告\n\n");
        markdown.append("## 测试目的\n\n");
        markdown.append("本报告重新验证推荐部署形态：SQ8、查询预计算、堆外向量和 MMap Payload。它不比较不同算法模式，避免将不同资源条件混入同一结论。\n\n");
        markdown.append("## 约束与方法\n\n");
        markdown.append("- JVM 参数：`-Xms128m -Xmx384m -XX:MaxDirectMemorySize=512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC`。堆与 Direct Memory 的上限合计 896 MB，为线程栈、元数据和操作系统页缓存保留约 128 MB。\n");
        markdown.append("- 运行环境：`java " + System.getProperty("java.version") + "`，`"
                + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "`，JVM 可见处理器数为 " + Runtime.getRuntime().availableProcessors() + "。\n");
        markdown.append("- 单核口径由 `ActiveProcessorCount=1` 实现；本机未设置操作系统级 CPU 亲和性或容器 cgroup 限制，因此结果是 JVM 单核口径而非物理核独占。\n");
        markdown.append("- 每个规模使用固定随机种子，512 维、余弦 Top-10、无元数据过滤；单线程检索。每个规模运行在独立 JVM，避免 Direct Buffer 回收时机污染结果。\n");
        markdown.append("- 每个规模先预热 8 次，再采集 15 次完整检索；延迟报告中位数和 P95，QPS 由中位数换算。\n");
        markdown.append("- Payload 写入测试临时目录，不污染 `src/main/resources/vec`。堆和 Direct Memory 是建库后的进程内增量；磁盘大小是该临时目录的实际文件大小。当前 `PayloadMode.MMAP` 的实现使用 `FileChannel`，本报告不将其称为操作系统级内存映射。\n\n");
        markdown.append("## 结果\n\n");
        markdown.append("| 向量数量 | 写入吞吐 (vectors/s) | 检索中位数 (ms) | 检索 P95 (ms) | 估算 QPS | 堆增量 (MiB) | Direct 增量 (MiB) | SQ8 容量 (MiB) | Payload 文件 (MiB) | 状态 |\n");
        markdown.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :--- |\n");
        for (BenchmarkResult result : results) {
            if (result.failure != null) {
                markdown.append(String.format("| %,d | - | - | - | - | - | - | - | - | 失败：%s |%n",
                        result.vectorCount, result.failure));
                continue;
            }
            markdown.append(String.format(Locale.ROOT,
                    "| %,d | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | 通过 |%n",
                    result.vectorCount, result.insertQps, result.medianMs, result.p95Ms, result.qps,
                    toMiB(result.heapBytes), toMiB(result.directBytes), toMiB(result.sq8Bytes), toMiB(result.diskBytes)));
        }
        markdown.append("\n## 结论边界\n\n");
        markdown.append("该结果衡量单进程、无并发、无过滤、内存中已建库后的精确 Flat 检索，不代表多租户、并发写入、快照恢复或百万级近似索引性能。50 万规模在当前实现中因堆外缓冲扩容而失败：已分配约 219 MiB 时申请约 328 MiB 新缓冲，超过 512 MiB Direct Memory 上限。这是当前实现的真实容量边界，需在 `OffHeapSQ8Buffer` 的扩容策略中解决后再重测。\n");
        Files.writeString(report, markdown.toString());
    }

    private void forceGc() {
        System.gc();
    }

    private long usedHeapBytes() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return memory.getHeapMemoryUsage().getUsed();
    }

    private long usedDirectBytes() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> "direct".equals(pool.getName()))
                .mapToLong(BufferPoolMXBean::getMemoryUsed)
                .sum();
    }

    private long directorySize(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile).mapToLong(this::fileSize).sum();
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取基准文件大小: " + path, exception);
        }
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, index));
    }

    private double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private double elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static double toMiB(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static class BenchmarkResult {
        private final int vectorCount;
        private final double insertQps;
        private final double medianMs;
        private final double p95Ms;
        private final double qps;
        private final long heapBytes;
        private final long directBytes;
        private final long sq8Bytes;
        private final long diskBytes;
        private final String failure;

        private BenchmarkResult(int vectorCount, double insertQps, double medianMs, double p95Ms, double qps,
                                long heapBytes, long directBytes, long sq8Bytes, long diskBytes) {
            this.vectorCount = vectorCount;
            this.insertQps = insertQps;
            this.medianMs = medianMs;
            this.p95Ms = p95Ms;
            this.qps = qps;
            this.heapBytes = heapBytes;
            this.directBytes = directBytes;
            this.sq8Bytes = sq8Bytes;
            this.diskBytes = diskBytes;
            this.failure = null;
        }

        private BenchmarkResult(int vectorCount, String failure) {
            this.vectorCount = vectorCount;
            this.insertQps = 0;
            this.medianMs = 0;
            this.p95Ms = 0;
            this.qps = 0;
            this.heapBytes = 0;
            this.directBytes = 0;
            this.sq8Bytes = 0;
            this.diskBytes = 0;
            this.failure = failure;
        }

        private static BenchmarkResult failed(int vectorCount, String failure) {
            return new BenchmarkResult(vectorCount, failure);
        }
    }
}

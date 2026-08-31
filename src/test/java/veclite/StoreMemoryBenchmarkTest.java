package veclite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.StorageType;
import veclite.model.VectorDocument;
import veclite.persistence.SnapshotFileStorage;
import veclite.persistence.VectorPersistenceStorage;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * 内存占用评估与耗时基准测试类。
 */
@Tag("benchmark")
public class StoreMemoryBenchmarkTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 测试 5 个 20 万（总计 100 万）512 维度的本地向量库内存开销、单线程创建与写入耗时及落盘保存。
     * 持久化文件自动保存至 src/main/resources/vec/ 目录下。
     */
    @Test
    @DisplayName("测试5个20万数量、512维度的本地向量库内存占用、写入耗时与快照刷盘")
    void testFiveStores200kMemoryUsage() {
        int storeCount = 5;
        int vectorCountPerStore = 200_000;
        int dimension = 512;

        System.out.println("==================================================");
        System.out.println("开始基准测试：单线程创建 " + storeCount + " 个 Store，每个 Store 插入 " + vectorCountPerStore + " 条 " + dimension + " 维向量（总计 100 万条向量）...");
        System.out.println("==================================================");

        // 指定持久化快照落盘路径为 src/main/resources/vec/
        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().setType(StorageType.SNAPSHOT_FILE);
        String resourcePath = new File("src/main/resources/vec").getAbsolutePath();
        properties.getStorage().getSnapshotFile().setBasePath(resourcePath);

        LocalVectorEngine engine = new LocalVectorEngine();
        VectorPersistenceStorage storage = new SnapshotFileStorage(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(engine, null, storage, properties, null);

        runGC();
        long initialMemory = getUsedMemoryMB();
        System.out.println("初始 JVM 堆内存使用量: " + initialMemory + " MB");

        Random random = new Random(42);
        long allStoresStartTime = System.currentTimeMillis();

        for (int s = 1; s <= storeCount; s++) {
            String storeName = "store_" + s;
            VectorStoreDefinition definition = new VectorStoreDefinition();
            definition.setStoreName(storeName);
            definition.setDimension(dimension);
            definition.setMaxCapacity(vectorCountPerStore + 1000);
            definition.setMetric("COSINE");

            client.createStore(storeName, definition);

            long storeStartTime = System.currentTimeMillis();
            long totalGenerateTimeMs = 0;
            long totalUpsertTimeMs = 0;

            System.out.println("\n[Store " + s + "/" + storeCount + "] 开始单线程生成并插入 " + vectorCountPerStore + " 条向量...");

            for (int i = 0; i < vectorCountPerStore; i++) {
                // 1. 测量向量数据与 Document 创建耗时
                long genStart = System.nanoTime();
                float[] vector = new float[dimension];
                for (int d = 0; d < dimension; d++) {
                    vector[d] = random.nextFloat();
                }

                VectorDocument doc = new VectorDocument();
                doc.setId("doc_" + s + "_" + i);
                doc.setText("示例测试文本内容 " + i);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("category", "test");
                metadata.put("index", i);
                doc.setMetadata(metadata);
                doc.setVector(vector);
                long genEnd = System.nanoTime();
                totalGenerateTimeMs += (genEnd - genStart);

                // 2. 测量单纯 upsert 写入耗时
                long upsertStart = System.nanoTime();
                client.upsert(storeName, doc);
                long upsertEnd = System.nanoTime();
                totalUpsertTimeMs += (upsertEnd - upsertStart);

                if ((i + 1) % 50000 == 0) {
                    System.out.println("  - Store [" + storeName + "] 已完成 " + (i + 1) + " 条向量写入...");
                }
            }
            long storeEndTime = System.currentTimeMillis();
            long totalStoreTimeMs = storeEndTime - storeStartTime;
            double genMs = totalGenerateTimeMs / 1_000_000.0;
            double upsertMs = totalUpsertTimeMs / 1_000_000.0;
            double avgUpsertUs = (totalUpsertTimeMs / 1000.0) / vectorCountPerStore;

            System.out.println("[Store " + s + "] 总结算：");
            System.out.println("  - 单 Store 总耗时: " + totalStoreTimeMs + " ms");
            System.out.println("  - 向量生成与 Doc 构建耗时: " + String.format("%.2f", genMs) + " ms");
            System.out.println("  - 纯 Upsert 写入总耗时: " + String.format("%.2f", upsertMs) + " ms");
            System.out.println("  - 平均单条向量 Upsert 写入耗时: " + String.format("%.3f", avgUpsertUs) + " μs (微秒)");
            System.out.println("  - 单线程 Upsert 吞吐量: " + String.format("%.0f", (vectorCountPerStore / (upsertMs / 1000.0))) + " ops/sec");

            runGC();
            long currentMem = getUsedMemoryMB();
            System.out.println("  - 当前 JVM 堆内存总量: " + currentMem + " MB（相比初始增加: " + (currentMem - initialMemory) + " MB）");
        }

        long allStoresEndTime = System.currentTimeMillis();
        long grandTotalTimeMs = allStoresEndTime - allStoresStartTime;

        runGC();
        long totalMemory = getUsedMemoryMB();
        long netMemoryUsed = totalMemory - initialMemory;

        System.out.println("\n==================================================");
        System.out.println("【基准测试最终汇总报告】");
        System.out.println("向量库数量: " + storeCount + " 个");
        System.out.println("单库向量数: " + vectorCountPerStore + " 条");
        System.out.println("向量维度: " + dimension + " 维");
        System.out.println("总向量条数: " + (storeCount * vectorCountPerStore) + " 条 (100 万条)");
        System.out.println("--------------------------------------------------");
        System.out.println("100 万向量总创建与写入总耗时: " + grandTotalTimeMs + " ms (" + String.format("%.2f", grandTotalTimeMs / 1000.0) + " 秒)");
        System.out.println("JVM 最终堆内存使用量: " + totalMemory + " MB");
        System.out.println("净增加堆内存占用约为: " + netMemoryUsed + " MB (" + String.format("%.2f", netMemoryUsed / 1024.0) + " GB)");
        System.out.println("平均每条 512 维向量及 Metadata 内存开销: " + String.format("%.2f", (netMemoryUsed * 1024.0 * 1024.0) / (storeCount * vectorCountPerStore)) + " 字节");
        System.out.println("==================================================");

        System.out.println("\n开始将 5 个 Store 的数据持久化刷盘保存至资源目录: " + resourcePath);
        long saveStart = System.currentTimeMillis();
        for (int s = 1; s <= storeCount; s++) {
            String storeName = "store_" + s;
            client.refresh(storeName);
            System.out.println("  - Store [" + storeName + "] 快照落盘成功。");
        }
        long saveEnd = System.currentTimeMillis();
        System.out.println("全量刷盘完成，耗时: " + (saveEnd - saveStart) + " ms");
    }

    /**
     * 对接本地 Docker/Ollama 运行的 bge-small-zh Embedding 服务生成向量测试。
     * 当本地 11434 端口的 Docker 容器启动时可运行此测试。
     */
    @Test
    @Disabled("当本地已启动 Ollama docker 容器时，手动取消该注解运行测试")
    @DisplayName("测试对接本地 Docker Ollama (bge-small-zh) 生成向量")
    void testOllamaDockerEmbedding() throws Exception {
        String ollamaUrl = "http://localhost:11434/api/embeddings";
        String modelName = "bge-small-zh";
        String prompt = "你好";

        System.out.println("正在请求本地 Docker Embedding 服务: " + ollamaUrl);

        URL url = new URL(ollamaUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("prompt", prompt);

        byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        System.out.println("HTTP 响应状态码: " + responseCode);

        if (responseCode == 200) {
            JsonNode root = objectMapper.readTree(conn.getInputStream());
            if (root.has("embedding") && root.get("embedding").isArray()) {
                JsonNode embedNode = root.get("embedding");
                int dim = embedNode.size();
                System.out.println("成功从 Ollama bge-small-zh 获得向量，向量维度: " + dim);
                float[] vector = new float[dim];
                for (int i = 0; i < dim; i++) {
                    vector[i] = (float) embedNode.get(i).asDouble();
                }
                System.out.println("向量前 5 个分量预览: [" + vector[0] + ", " + vector[1] + ", " + vector[2] + ", " + vector[3] + ", " + vector[4] + "...]");
            }
        } else {
            System.err.println("请求失败，请确保本地 Docker 已正常启动并加载了 bge-small-zh 模型。");
        }
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

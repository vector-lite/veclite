package veclite.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import veclite.api.VectorStoreDefinition;
import veclite.persistence.NoopVectorPersistenceStorage;
import veclite.persistence.mongo.MongoVectorPersistenceStorage;
import veclite.persistence.mongo.MongoVectorDocumentRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 Ollama embedding 数据源（bge-small-zh，localhost:11434）端到端联调测试：
 * 文本 → bge-small-zh 向量化 → 写入 Store → searchByText 检索。
 * <p>
 * 依赖外部服务，标记 {@code @Tag("manual")}，由
 * {@code ./gradlew manualTest --tests 'veclite.embedding.OllamaEmbeddingManualTest'} 手动执行。
 */
@Tag("manual")
class OllamaEmbeddingManualTest {

    private static final String MODEL = "bge-small-zh";
    private static final int BGE_SMALL_ZH_DIMENSION = 512;

    /** 数据源由 registry（数据库维护路径）承载，测试用内存版持久化端口 */
    private EmbeddingModelRegistry registry() {
        VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
        config.setName(MODEL);
        config.setVersion("1");
        config.setProvider("ollama");
        config.setUrl("http://localhost:11434/api/embeddings");
        config.setTimeoutMillis(10000);
        config.setBatchSize(16);
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(new InMemoryModelStore());
        registry.save(config);
        return registry;
    }

    /** 内存版模型配置持久化端口 */
    private static final class InMemoryModelStore implements EmbeddingModelStore {
        private final Map<String, VectorLiteProperties.ModelConfig> rows = new java.util.LinkedHashMap<>();

        @Override
        public List<VectorLiteProperties.ModelConfig> loadAll() {
            return new java.util.ArrayList<>(rows.values());
        }

        @Override
        public void save(VectorLiteProperties.ModelConfig config) {
            rows.put(config.getName() + "\u001F" + config.getVersion(), config);
        }

        @Override
        public boolean delete(String name, String version) {
            return rows.remove(name + "\u001F" + version) != null;
        }

        @Override
        public void saveDefault(EmbeddingModelRef ref) {
            this.defaultRef = ref;
        }

        @Override
        public EmbeddingModelRef loadDefault() {
            return defaultRef;
        }

        private EmbeddingModelRef defaultRef;
    }

    @Test
    @DisplayName("单条与批量向量化：维度 512，批量结果与输入顺序对位")
    void embedSingleAndBatch() {
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(registry());

        List<Float> vector = provider.embed(MODEL, "1", "向量数据库是一种高效检索语义相似内容的技术");
        assertEquals(BGE_SMALL_ZH_DIMENSION, vector.size());

        List<List<Float>> batch = provider.embedBatch(MODEL, "1",
                List.of("机器学习模型", "今天天气不错", "向量检索引擎"));
        assertEquals(3, batch.size());
        batch.forEach(v -> assertEquals(BGE_SMALL_ZH_DIMENSION, v.size()));
    }

    @Test
    @DisplayName("端到端：文本写入自动向量化，searchByText 语义命中正确文档")
    void textUpsertAndSemanticSearch() {
        VectorLiteProperties properties = new VectorLiteProperties();
        EmbeddingModelRegistry registry = registry();
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(registry);
        LocalVectorEngine engine = new LocalVectorEngine(properties);
        VectorEngineClientImpl client = new VectorEngineClientImpl(
                engine, provider, new NoopVectorPersistenceStorage(), properties, registry);

        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName("ollama-it");
        definition.setDimension(BGE_SMALL_ZH_DIMENSION);
        definition.setMetric("COSINE");
        definition.setMaxCapacity(1000);
        definition.setEmbeddingModel(MODEL);
        client.createStore("ollama-it", definition);

        client.upsertBatch("ollama-it", List.of(
                new VectorDocument("d1", null, "向量数据库用于高效检索语义相似的内容", Map.of("topic", "db")),
                new VectorDocument("d2", null, "今天下午的会议改到三点开始", Map.of("topic", "meeting")),
                new VectorDocument("d3", null, "红烧肉的做法需要先焯水再炖煮", Map.of("topic", "cook"))));

        List<VectorSearchResult> results = client.searchByText(search("ollama-it", "如何存储和检索文章的语义向量"));
        assertEquals("d1", results.get(0).getId());
        assertTrue(results.get(0).getScore() > results.get(results.size() - 1).getScore());
    }

    @Test
    @DisplayName("端到端（MongoDB 真相源）：文本写入落库，重启发现后 searchByText 依然命中")
    void textUpsertWithMongoPersistenceAndRediscovery() {
        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().setType(veclite.model.StorageType.MONGODB);
        properties.getStorage().getMongodb().setUri(
                System.getenv().getOrDefault("MONGO_URI",
                        "mongodb://admin:12345678@localhost:27017/?authSource=admin"));
        properties.getStorage().getMongodb().setDatabase("veclite_manual_test");

        MongoVectorDocumentRepository repository = new MongoVectorDocumentRepository(properties);
        try {
            repository.deleteStoreMetadata("ollama-mongo-it");
            repository.deleteAll("ollama-mongo-it");

            EmbeddingModelRegistry registry = registry();
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(registry);
            MongoVectorPersistenceStorage storage = new MongoVectorPersistenceStorage(repository, properties);
            VectorEngineClientImpl client = new VectorEngineClientImpl(
                    new LocalVectorEngine(properties), provider, storage, properties, registry);

            VectorStoreDefinition definition = new VectorStoreDefinition();
            definition.setStoreName("ollama-mongo-it");
            definition.setDimension(BGE_SMALL_ZH_DIMENSION);
            definition.setEmbeddingModel(MODEL);
            client.createStore("ollama-mongo-it", definition);
            client.upsertBatch("ollama-mongo-it", List.of(
                    new VectorDocument("m1", null, "向量索引可以加速相似度检索", Map.of()),
                    new VectorDocument("m2", null, "明天记得交物业费", Map.of())));

            // 模拟重启：全新 Client 实例从真相源发现并重建
            VectorEngineClientImpl restarted = new VectorEngineClientImpl(
                    new LocalVectorEngine(properties), provider, storage, properties, registry);
            List<VectorSearchResult> results =
                    restarted.searchByText(search("ollama-mongo-it", "语义检索的性能优化"));

            assertEquals("m1", results.get(0).getId());
        } finally {
            repository.deleteStoreMetadata("ollama-mongo-it");
            repository.deleteAll("ollama-mongo-it");
            repository.close();
        }
    }

    private VectorSearchRequest search(String storeName, String text) {
        VectorSearchRequest request = new VectorSearchRequest();
        request.setStoreName(storeName);
        request.setQueryText(text);
        request.setTopK(3);
        return request;
    }
}

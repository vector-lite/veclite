package veclite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import veclite.api.EmbeddingProvider;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingModelRef;
import veclite.embedding.EmbeddingModelRegistry;
import veclite.embedding.EmbeddingModelStore;
import veclite.embedding.EmbeddingService;
import veclite.engine.LocalVectorEngine;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.EmbeddingModelInfo;
import veclite.model.VectorDocument;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import veclite.persistence.NoopVectorPersistenceStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本自动 Embed 能力回归测试：
 * 1. 仅携带 text 的文档在 upsert 时自动调用绑定的 Embedding 模型（名称+版本）向量化后写入
 * 2. Store 与 Embedding 模型的绑定关系创建后不可变（同名同版本幂等，变更即拒绝）
 * 3. 绑定未配置的模型在创建 Store 时 fail-fast
 * 4. 批量 upsert 按模型 batchSize 分批调用 Embedding 服务
 * 5. Embedding 模型管理（列表/查询）
 */
public class EmbeddingFlowTest {

    private static final int DIM = 8;
    private static final String MODEL = "mock-embed";
    private static final String VERSION = "v2";

    private VectorLiteProperties properties;
    private RecordingEmbeddingProvider provider;
    private EmbeddingService embeddingService;
    private LocalVectorEngine engine;
    private VectorEngineClientImpl client;

    @BeforeEach
    public void setUp() {
        properties = new VectorLiteProperties();
        // 模型配置改为数据库维护：测试用内存版持久化端口承载
        VectorLiteProperties.ModelConfig model = new VectorLiteProperties.ModelConfig();
        model.setName(MODEL);
        model.setVersion(VERSION);
        model.setUrl("http://localhost/mock-embed");
        model.setTimeoutMillis(1000);
        model.setBatchSize(4);
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(new InMemoryModelStore());
        registry.save(model);

        provider = new RecordingEmbeddingProvider();
        embeddingService = new EmbeddingService(provider, registry);
        engine = new LocalVectorEngine(properties, embeddingService);
        client = new VectorEngineClientImpl(engine, provider, new NoopVectorPersistenceStorage(), properties, registry);
    }

    private VectorStoreDefinition definition(String model, String version) {
        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setDimension(DIM);
        def.setMetric("COSINE");
        def.setMaxCapacity(1000);
        def.setEmbeddingModel(model);
        def.setEmbeddingModelVersion(version);
        return def;
    }

    @Test
    public void testTextUpsertAutoEmbedded() {
        client.createStore("s1", definition(MODEL, null));

        VectorDocument doc = new VectorDocument("d1", null, "hello vector world", new HashMap<>());
        client.upsert("s1", doc);

        assertNotNull(doc.getVector(), "仅携带 text 的文档应被自动向量化");
        assertEquals(DIM, doc.getVector().length);
        assertEquals(MODEL, provider.lastModel);
        assertEquals(VERSION, provider.lastVersion, "未显式指定版本时应使用模型配置的默认版本");
        assertEquals(1, client.stats("s1").getDocCount());

        // 绑定版本应在创建时被固化到定义上
        assertEquals(VERSION, engine.getStore("s1").getDefinition().getEmbeddingModelVersion());

        // 文本检索走同一绑定模型
        VectorSearchRequest request = new VectorSearchRequest();
        request.setStoreName("s1");
        request.setQueryText("hello vector world");
        request.setTopK(1);
        List<VectorSearchResult> results = client.searchByText(request);
        assertEquals("d1", results.get(0).getId(), "相同文本应精确召回自身");
        assertEquals(MODEL, provider.lastModel);
        assertEquals(VERSION, provider.lastVersion);
    }

    @Test
    public void testBatchUpsertEmbedsInChunks() {
        client.createStore("s2", definition(MODEL, VERSION));

        List<VectorDocument> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            docs.add(new VectorDocument("d" + i, null, "text chunk " + i, new HashMap<>()));
        }
        client.upsertBatch("s2", docs);

        // batchSize=4：10 条应拆成 4+4+2 三批
        assertEquals(3, provider.calls.size());
        assertEquals(4, provider.calls.get(0).batchSize);
        assertEquals(4, provider.calls.get(1).batchSize);
        assertEquals(2, provider.calls.get(2).batchSize);
        for (VectorDocument doc : docs) {
            assertNotNull(doc.getVector(), "批量文档均应被向量化");
        }
        assertEquals(10, client.stats("s2").getDocCount());
    }

    @Test
    public void testPrecomputedVectorWinsOverText() {
        client.createStore("s3", definition(MODEL, VERSION));

        float[] precomputed = new float[DIM];
        for (int i = 0; i < DIM; i++) precomputed[i] = 0.5f;
        VectorDocument doc = new VectorDocument("d1", precomputed, "should not be embedded", new HashMap<>());
        client.upsert("s3", doc);

        assertTrue(provider.calls.isEmpty(), "已携带 vector 的文档不应再调用 Embedding 服务");
        assertSame(precomputed, doc.getVector());
    }

    @Test
    public void testEmbeddingBindingIsImmutable() {
        client.createStore("s4", definition(MODEL, null));

        // 同名同版本（版本归一化后一致）幂等
        assertDoesNotThrow(() -> client.createStore("s4", definition(MODEL, null)));

        // 变更模型名被拒绝
        assertThrows(LocalVectorEngine.ImmutableEmbeddingBindingException.class,
                () -> client.createStore("s4", definition("other-model", VERSION)));

        // 变更版本被拒绝
        assertThrows(LocalVectorEngine.ImmutableEmbeddingBindingException.class,
                () -> client.createStore("s4", definition(MODEL, "v3")));

        // 未绑定 Store 创建后再补绑定同样被拒绝（null -> model 也算变更）
        client.createStore("s5", definition(null, null));
        assertThrows(LocalVectorEngine.ImmutableEmbeddingBindingException.class,
                () -> client.createStore("s5", definition(MODEL, VERSION)));

        // 原有 Store 不受影响
        assertEquals(VERSION, engine.getStore("s4").getDefinition().getEmbeddingModelVersion());
        assertNull(engine.getStore("s5").getDefinition().getEmbeddingModel());
    }

    @Test
    public void testUnknownModelRejectedAtCreation() {
        assertThrows(IllegalArgumentException.class,
                () -> client.createStore("s6", definition("no-such-model", "1")));
        assertFalse(engine.hasStore("s6"), "校验失败的 Store 不应被创建");
    }

    @Test
    public void testTextUpsertWithoutBindingFails() {
        client.createStore("s7", definition(null, null));

        VectorDocument doc = new VectorDocument("d1", null, "no embedding bound", new HashMap<>());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.upsert("s7", doc));
        assertTrue(ex.getMessage().contains("no embedding model bound"),
                "错误信息应说明 Store 未绑定 Embedding 模型");
    }

    @Test
    public void testDocumentWithoutVectorAndTextFails() {
        client.createStore("s8", definition(MODEL, VERSION));

        VectorDocument doc = new VectorDocument("d1", null, null, new HashMap<>());
        assertThrows(IllegalArgumentException.class, () -> client.upsert("s8", doc));
    }

    @Test
    public void testEmbeddingModelManagement() {
        List<EmbeddingModelInfo> models = embeddingService.listModels();
        assertEquals(1, models.size());
        EmbeddingModelInfo info = models.get(0);
        assertEquals(MODEL, info.getName());
        assertEquals(VERSION, info.getVersion());
        assertEquals(4, info.getBatchSize());
        assertTrue(info.isDefaultModel(), "唯一模型即默认模型");

        assertNotNull(embeddingService.getModel(MODEL));
        assertNull(embeddingService.getModel("unknown"));
        assertTrue(embeddingService.hasModel(MODEL));
        assertFalse(embeddingService.hasModel("unknown"));
    }

    /**
     * 可记录调用信息的确定性 Embedding 提供者（不走网络）。
     */
    static class RecordingEmbeddingProvider implements EmbeddingProvider {

        static class Call {
            final String model;
            final String version;
            final int batchSize;
            final int dimension;

            Call(String model, String version, int batchSize, int dimension) {
                this.model = model;
                this.version = version;
                this.batchSize = batchSize;
                this.dimension = dimension;
            }
        }

        final List<Call> calls = new ArrayList<>();
        volatile String lastModel;
        volatile String lastVersion;
        volatile int lastDimension;

        @Override
        public List<Float> embed(String modelName, String modelVersion, String text, int dimension) {
            lastModel = modelName;
            lastVersion = modelVersion;
            lastDimension = dimension;
            calls.add(new Call(modelName, modelVersion, 1, dimension));
            return embedText(text);
        }

        @Override
        public List<List<Float>> embedBatch(String modelName, String modelVersion, List<String> texts, int dimension) {
            lastModel = modelName;
            lastVersion = modelVersion;
            lastDimension = dimension;
            calls.add(new Call(modelName, modelVersion, texts.size(), dimension));
            List<List<Float>> result = new ArrayList<>(texts.size());
            for (String text : texts) {
                result.add(embedText(text));
            }
            return result;
        }

        private List<Float> embedText(String text) {
            List<Float> vector = new ArrayList<>(DIM);
            for (int i = 0; i < DIM; i++) {
                char c = text.charAt(i % text.length());
                vector.add((c % 26) / 26.0f);
            }
            return vector;
        }
    }

    /** 内存版模型配置持久化端口，模拟数据库集合 */
    private static final class InMemoryModelStore implements EmbeddingModelStore {
        private final Map<String, VectorLiteProperties.ModelConfig> rows = new HashMap<>();

        @Override
        public List<VectorLiteProperties.ModelConfig> loadAll() {
            return new ArrayList<>(rows.values());
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
}

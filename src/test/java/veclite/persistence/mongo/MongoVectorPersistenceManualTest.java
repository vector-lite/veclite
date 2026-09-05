package veclite.persistence.mongo;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorEngine;
import veclite.engine.LocalVectorStore;
import veclite.engine.VectorEngineClientImpl;
import veclite.model.DeleteResult;
import veclite.model.FilterExpression;
import veclite.model.QuantizationType;
import veclite.model.StorageType;
import veclite.model.VectorDocument;
import veclite.model.VectorDocumentPage;
import veclite.model.VectorSearchRequest;
import veclite.model.VectorSearchResult;
import veclite.persistence.DocumentBackedPersistence;
import veclite.persistence.VectorDocumentEntity;
import veclite.persistence.VectorDocumentRepository;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoDB 单一真相源持久化端到端联调测试（依赖本机 local-mongo docker 容器，默认 localhost:27017）。
 * <p>
 * 依赖外部服务，按测试分类规范标记 {@code @Tag("manual")}，由
 * {@code ./gradlew test --tests 'veclite.persistence.mongo.MongoVectorPersistenceManualTest'} 手动执行；
 * 常规 {@code ./gradlew test} 自动排除。测试使用独立数据库并在前后清理，不产生持久化垃圾数据。
 */
@Tag("manual")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoVectorPersistenceManualTest {

    private static final String TEST_DATABASE = "veclite_manual_test";
    private static final String STORE_NAME = "it_store";

    /** 连接串可通过环境变量 MONGO_URI 覆盖；默认指向本机 local-mongo 容器（root 账号，authSource=admin） */
    private static final String MONGO_URI =
            System.getenv().getOrDefault("MONGO_URI", "mongodb://admin:12345678@localhost:27017/?authSource=admin");

    private static VectorLiteProperties properties;
    private static VectorDocumentRepository repository;
    private static DocumentBackedPersistence storage;
    private static VectorEngineClientImpl client;

    @BeforeAll
    static void setUp() {
        properties = newProperties();
        repository = new MongoVectorDocumentRepository(properties);
        storage = new MongoVectorPersistenceStorage(repository, properties);

        cleanDatabase();
        client = new VectorEngineClientImpl(new LocalVectorEngine(properties), null, storage, properties, null);
    }

    @AfterAll
    static void tearDown() {
        cleanDatabase();
        repository.close();
    }

    private static VectorLiteProperties newProperties() {
        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().setType(StorageType.MONGODB);
        properties.getStorage().getMongodb().setUri(MONGO_URI);
        properties.getStorage().getMongodb().setDatabase(TEST_DATABASE);
        return properties;
    }

    private static void cleanDatabase() {
        try (var mongo = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = mongo.getDatabase(TEST_DATABASE);
            // Store 集合按 storeName 命名（无共享文档集合），必须逐个清理，
            // 否则软删 tombstone 等残留行会跨测试运行累积
            db.getCollection(STORE_NAME).drop();
            db.getCollection("it_store_sq8").drop();
            db.getCollection("veclite_store_meta").drop();
        }
    }

    private static VectorStoreDefinition definition(String name, int dimension, QuantizationType quantization) {
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(name);
        definition.setDimension(dimension);
        definition.setMetric("COSINE");
        definition.setMaxCapacity(100000);
        definition.setQuantization(quantization);
        return definition;
    }

    private static List<VectorDocument> sampleDocs() {
        return List.of(
                new VectorDocument("d1", new float[]{0.1f, 0.0f, 0.0f, 0.0f}, "alpha text", Map.of("category", "a")),
                new VectorDocument("d2", new float[]{0.0f, 0.9f, 0.0f, 0.0f}, "beta text", Map.of("category", "b")),
                new VectorDocument("d3", new float[]{0.0f, 0.0f, 0.8f, 0.1f}, "gamma text", Map.of("category", "a")));
    }

    private static VectorSearchRequest request(float[] vector) {
        VectorSearchRequest request = new VectorSearchRequest();
        request.setStoreName(STORE_NAME);
        request.setQueryVector(vector);
        request.setTopK(10);
        return request;
    }

    @Test
    @Order(1)
    @DisplayName("写透 upsert：真相源与内存同步落库，元数据登记进真相源")
    void writeThroughUpsertShouldPersistDocuments() {
        client.createStore(STORE_NAME, definition(STORE_NAME, 4, QuantizationType.NONE));
        client.upsertBatch(STORE_NAME, sampleDocs());

        assertEquals(3, repository.count(STORE_NAME));
        assertNotNull(repository.findStoreMetadata(STORE_NAME).orElse(null));
    }

    @Test
    @Order(2)
    @DisplayName("reload 整库重建：向量、文本、元数据从真相源位级还原，检索结果一致")
    void reloadShouldRebuildFromMongo() {
        client.reload(STORE_NAME);

        VectorDocumentPage page = client.listDocuments(STORE_NAME, 1, 10);
        assertEquals(3, page.getItems().size());
        VectorDocument d2 = page.getItems().stream()
                .filter(d -> "d2".equals(d.getId())).findFirst().orElseThrow();
        assertEquals("beta text", d2.getText());
        assertEquals("b", d2.getMetadata().get("category"));

        // listDocuments 管理页按约定不回传向量，向量位级还原直接校验真相源实体
        assertArrayEquals(new float[]{0.0f, 0.9f, 0.0f, 0.0f}, scanEntity("d2").getVector());

        List<VectorSearchResult> results = client.searchByVector(request(new float[]{0.0f, 0.9f, 0.0f, 0.0f}));
        assertEquals("d2", results.get(0).getId());
    }

    /** 按 docId 查找文档实体（含 tombstone：scan 已排除软删行，这里走增量扫描通道） */
    private VectorDocumentEntity scanEntity(String docId) {
        Iterator<VectorDocumentEntity> cursor = repository.scanUpdatedSince(STORE_NAME, Instant.EPOCH);
        try {
            while (cursor.hasNext()) {
                VectorDocumentEntity entity = cursor.next();
                if (docId.equals(entity.getDocId())) {
                    return entity;
                }
            }
        } finally {
            if (cursor instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    // 游标耗尽或提前退出时的释放失败不影响断言结果
                }
            }
        }
        throw new AssertionError("Document not found in persistence: " + docId);
    }

    @Test
    @Order(3)
    @DisplayName("deleteByIds 写透删除：真相源软删（tombstone），reload 后保持删除语义")
    void deleteByIdsShouldPropagateToMongo() {
        DeleteResult result = client.deleteByIds(STORE_NAME, List.of("d1"));
        assertEquals(1, result.getDeletedCount());
        // 软删除保留 tombstone 行：活跃集合不再包含 d1，物理行数不变
        assertFalse(activeIds().contains("d1"));
        assertTrue(scanEntity("d1").isDeleted());

        client.reload(STORE_NAME);
        assertEquals(2, client.listDocuments(STORE_NAME, 1, 10).getItems().size());
    }

    @Test
    @Order(4)
    @DisplayName("deleteByFilter 写透删除：按过滤条件命中 ID 并同步真相源")
    void deleteByFilterShouldPropagateToMongo() {
        DeleteResult result = client.deleteByFilter(STORE_NAME, FilterExpression.eq("category", "b"));
        assertEquals(1, result.getDeletedCount());
        assertFalse(activeIds().contains("d2"));

        client.reload(STORE_NAME);
        assertEquals("d3", client.listDocuments(STORE_NAME, 1, 10).getItems().get(0).getId());
    }

    @Test
    @Order(5)
    @DisplayName("重启发现：新 Client 实例从元数据自动发现存量 Store 并装载数据")
    void newClientShouldDiscoverPersistedStore() {
        // 未在 properties.stores 中声明，仅依赖真相源元数据发现
        VectorEngineClientImpl restartedClient =
                new VectorEngineClientImpl(new LocalVectorEngine(properties), null, storage, properties, null);

        List<VectorSearchResult> results =
                restartedClient.searchByVector(request(new float[]{0.0f, 0.0f, 0.8f, 0.1f}));
        assertEquals("d3", results.get(0).getId());
    }

    @Test
    @Order(6)
    @DisplayName("SQ8 冻结态往返：量化字节 + 逐维参数位级还原，不经过反量化-重量化衰减")
    void sq8FrozenStoreShouldRoundTripBitExact() {
        String sq8Store = "it_store_sq8";
        LocalVectorStore source = new LocalVectorStore(definition(sq8Store, 4, QuantizationType.SQ8), properties);

        float[] minPerDim = {-1.0f, -2.0f, -0.5f, 0.0f};
        float[] scalePerDim = {2.0f / 255, 4.0f / 255, 1.0f / 255, 1.0f / 255};
        source.restoreFrozenParams(minPerDim, scalePerDim);
        source.restoreDocumentWithSQ8(
                new VectorDocument("q1", null, "quantized one", Map.of("k", "v1")),
                new byte[]{10, 20, 30, 40});
        source.restoreDocumentWithSQ8(
                new VectorDocument("q2", null, "quantized two", Map.of("k", "v2")),
                new byte[]{-10, -20, 100, 5});

        storage.saveStore(source);

        assertArrayEquals(minPerDim, repository.findStoreMetadata(sq8Store).get().getSq8MinPerDim());
        assertArrayEquals(scalePerDim, repository.findStoreMetadata(sq8Store).get().getSq8ScalePerDim());

        LocalVectorStore restored = new LocalVectorStore(definition(sq8Store, 4, QuantizationType.SQ8), properties);
        storage.loadStore(restored);

        assertEquals(source.getActiveCount(), restored.getActiveCount());
        assertTrue(restored.isSQ8Frozen());

        byte[] expectedBytes = new byte[4];
        byte[] actualBytes = new byte[4];
        for (int offset = 0; offset < source.getActiveCount(); offset++) {
            source.copySQ8VectorFromBuffer(offset, expectedBytes);
            restored.copySQ8VectorFromBuffer(offset, actualBytes);
            assertArrayEquals(expectedBytes, actualBytes, "SQ8 byte mismatch at offset " + offset);
        }
        assertEquals("quantized two", restored.listDocuments(1, 10, false).get(1).getText());
    }

    @Test
    @Order(7)
    @DisplayName("saveStore 集合级对账：软删真相源滞留行并同步 activeCount，已一致文档不重写")
    void saveStoreShouldReconcileStaleRows() {
        // 直接向真相源插入一条内存中不存在的滞留行
        repository.upsertBatch(STORE_NAME, List.of(
                veclite.persistence.VectorDocumentEntity.float32(
                        "ghost", "stale", Map.of(), new float[]{0.3f, 0.3f, 0.3f, 0.3f}, null)));
        assertTrue(activeIds().contains("ghost"));

        LocalVectorStore store = new LocalVectorStore(definition(STORE_NAME, 4, QuantizationType.NONE), properties);
        storage.saveStore(store);

        // 滞留行被软删（tombstone 保留），活跃集合与内存 activeCount 一致
        assertTrue(scanEntity("ghost").isDeleted());
        assertFalse(activeIds().contains("ghost"));
        assertEquals(store.getActiveCount(), activeIds().size());
        assertEquals(store.getActiveCount(),
                repository.findStoreMetadata(STORE_NAME).get().getActiveCount());
    }

    /** 真相源活跃（未软删）文档 ID 集合 */
    private List<String> activeIds() {
        return repository.listDocumentIds(STORE_NAME);
    }
}

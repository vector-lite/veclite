package veclite.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreMetadata;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorStore;
import veclite.model.QuantizationType;
import veclite.model.StorageType;
import veclite.model.StoreSyncResult;
import veclite.model.VectorDocument;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档型持久化编排逻辑回归：集合级对账（不重写已一致文档）、增量同步（水位推进/快检/跳过）、
 * 装载水位基线与 tombstone 语义。使用内存版仓储，无外部依赖、秒级完成。
 */
class AbstractDocumentPersistenceTest {

    private static final String STORE = "orchestration_store";

    // ---- 对账（saveStore）----

    @Test
    @DisplayName("对账：真相源与内存一致的文档不重写，内容漂移不被内存覆盖")
    void reconcileMustNotRewriteConsistentDocuments() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);

        // 真相源先持有原始向量（模拟写透落库），内存随后被另一节点改写为不同内容
        repository.upsertBatch(STORE, List.of(entity("a", new float[]{9.0f, 9.0f})));
        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);
        store.upsert(doc("a", new float[]{1.0f, 2.0f}));

        long upsertBatchesBefore = repository.upsertBatchCalls.get();
        persistence.saveStore(store);

        // 集合双方一致（id 相同）：真相源原始向量必须原样保留，不被内存值覆盖
        assertArrayEquals(new float[]{9.0f, 9.0f}, repository.getRaw(STORE, "a").orElseThrow().getVector());
        assertEquals(upsertBatchesBefore, repository.upsertBatchCalls.get());
    }

    @Test
    @DisplayName("对账：软删真相源滞留行，补齐真相源缺失文档，同步元数据 activeCount")
    void reconcileRepairsMissingAndStaleRows() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);

        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);
        store.upsert(doc("keep", new float[]{1.0f, 0.0f}));
        repository.putRaw(STORE, entity("ghost", new float[]{0.5f, 0.5f}));

        persistence.saveStore(store);

        assertTrue(repository.getRaw(STORE, "ghost").orElseThrow().isDeleted(), "stale row must be tombstoned");
        assertTrue(repository.listDocumentIds(STORE).contains("keep"));
        assertFalse(repository.getRaw(STORE, "keep").orElseThrow().isDeleted());

        // 内存独有文档（真相源缺失）被补齐：先清空真相源再对账验证补齐路径
        InMemoryVectorDocumentRepository emptyTruth = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence repairing = new TestPersistence(emptyTruth);
        LocalVectorStore source = newStore(emptyTruth, 2, QuantizationType.NONE);
        source.upsert(doc("d1", new float[]{1.0f, 2.0f}));
        source.upsert(doc("d2", new float[]{3.0f, 4.0f}));
        repairing.saveStore(source);
        assertArrayEquals(new float[]{1.0f, 2.0f}, emptyTruth.getRaw(STORE, "d1").orElseThrow().getVector());
        assertEquals(2, emptyTruth.findStoreMetadata(STORE).orElseThrow().getActiveCount());
    }

    @Test
    @DisplayName("对账回归：SQ8 冻结库不把真相源原始 Float32 覆盖为反量化近似值")
    void reconcileMustNotDegradeSq8TruthSource() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);
        LocalVectorStore store = newStore(repository, 2, QuantizationType.SQ8);
        store.getDefinition().setMaxCapacity(2);

        float[] vectorA = {1.0f, 2.0f};
        float[] vectorB = {-3.5f, 0.25f};
        // 写透先落原始向量（生产写路径顺序），内存随后量化
        persistence.upsertDocuments(store, List.of(doc("a", vectorA), doc("b", vectorB)));
        store.upsert(doc("a", vectorA));
        store.upsert(doc("b", vectorB));
        assertTrue(store.isSQ8Frozen());

        long upsertBatchesBefore = repository.upsertBatchCalls.get();
        persistence.saveStore(store);

        // 集合一致时不发生任何写入：真相源保持原始 Float32，而非量化-反量化往返后的近似值
        assertArrayEquals(vectorA, repository.getRaw(STORE, "a").orElseThrow().getVector());
        assertArrayEquals(vectorB, repository.getRaw(STORE, "b").orElseThrow().getVector());
        assertEquals(upsertBatchesBefore, repository.upsertBatchCalls.get());
    }

    // ---- 增量同步（incrementalSync）----

    @Test
    @DisplayName("增量同步：应用水位后的 upsert 与删除，并推进水位")
    void incrementalSyncAppliesUpsertsAndDeletes() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.now().plusSeconds(60));
        repository.clock = clock::get;
        AbstractDocumentPersistence persistence = new TestPersistence(repository);

        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);
        // d1 先写透真相源再进内存（生产写路径顺序），删除才能产生 tombstone
        persistence.upsertDocuments(store, List.of(doc("d1", new float[]{1.0f, 0.0f})));
        store.upsert(doc("d1", new float[]{1.0f, 0.0f}));
        establishBaseline(persistence, repository, store, clock.get());

        // 另一节点：新增 d2、软删 d1
        clock.set(clock.get().plusSeconds(10));
        repository.upsertBatch(STORE, List.of(entity("d2", new float[]{0.0f, 1.0f})));
        clock.set(clock.get().plusSeconds(10));
        repository.deleteByIds(STORE, List.of("d1"));
        Instant expectedWatermark = clock.get();

        StoreSyncResult result = persistence.incrementalSync(store);

        assertEquals(1, result.appliedUpserts());
        assertEquals(1, result.appliedDeletes());
        assertEquals(expectedWatermark, result.watermark());
        assertNull(store.getDocument("d1", false), "tombstoned document must be removed from memory");
        assertNotNull(store.getDocument("d2", false), "remote upsert must be applied to memory");
        assertEquals(expectedWatermark,
                repository.findStoreMetadata(STORE).orElseThrow().getSyncWatermark());
    }

    @Test
    @DisplayName("增量同步：无变更时走快检短路，不拉取游标")
    void incrementalSyncShortCircuitsWhenNoChanges() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);
        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);
        store.upsert(doc("d1", new float[]{1.0f, 0.0f}));
        establishBaseline(persistence, repository, store, Instant.now().plusSeconds(60));

        StoreSyncResult result = persistence.incrementalSync(store);

        assertEquals(0, result.appliedUpserts());
        assertEquals(0, result.appliedDeletes());
        assertNotNull(result.watermark());
        assertEquals(1, store.getActiveCount());
    }

    @Test
    @DisplayName("增量同步：水位基线缺失时跳过并保持空结果")
    void incrementalSyncSkipsWithoutWatermarkBaseline() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);
        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);
        store.upsert(doc("d1", new float[]{1.0f, 0.0f}));

        StoreSyncResult result = persistence.incrementalSync(store);

        assertEquals(0, result.appliedUpserts());
        assertEquals(0, result.appliedDeletes());
        assertNull(result.watermark());
        // 未同步不影响内存数据
        assertNotNull(store.getDocument("d1", false));
    }

    // ---- 整库装载（loadStore）----

    @Test
    @DisplayName("整库装载：排除软删除行，并建立增量同步水位基线")
    void loadStoreExcludesTombstonesAndBaselinesWatermark() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);

        repository.upsertBatch(STORE, List.of(entity("alive", new float[]{1.0f, 0.0f})));
        repository.upsertBatch(STORE, List.of(entity("tombstoned", new float[]{0.0f, 1.0f})));
        repository.deleteByIds(STORE, List.of("tombstoned"));

        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);
        persistence.loadStore(store);

        assertNotNull(store.getDocument("alive", false));
        assertNull(store.getDocument("tombstoned", false));
        Instant watermark = repository.findStoreMetadata(STORE).orElseThrow().getSyncWatermark();
        assertNotNull(watermark, "full load must establish the sync watermark baseline");
    }

    // ---- 写透与 tombstone ----

    @Test
    @DisplayName("写透 upsert 复活被软删除的 docId")
    void writeThroughUpsertResurrectsTombstonedDocument() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);
        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);

        persistence.upsertDocuments(store, List.of(doc("d1", new float[]{1.0f, 0.0f})));
        persistence.deleteDocuments(STORE, List.of("d1"));
        assertTrue(repository.getRaw(STORE, "d1").orElseThrow().isDeleted());

        persistence.upsertDocuments(store, List.of(doc("d1", new float[]{2.0f, 0.0f})));

        VectorDocumentEntity resurrected = repository.getRaw(STORE, "d1").orElseThrow();
        assertFalse(resurrected.isDeleted());
        assertArrayEquals(new float[]{2.0f, 0.0f}, resurrected.getVector());
    }

    @Test
    @DisplayName("元数据保存：入参无水位时保留库中现值（createStore 等场景不抹掉基线）")
    void saveStoreMetadataPreservesExistingWatermark() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        AbstractDocumentPersistence persistence = new TestPersistence(repository);
        LocalVectorStore store = newStore(repository, 2, QuantizationType.NONE);

        Instant baseline = Instant.now();
        VectorStoreMetadata metadata = VectorStoreMetadata.fromDefinition(store.getDefinition());
        metadata.setSyncWatermark(baseline);
        repository.saveStoreMetadata(metadata);

        persistence.saveStoreMetadata(store);

        assertEquals(baseline, repository.findStoreMetadata(STORE).orElseThrow().getSyncWatermark());
    }

    // ---- 测试夹具 ----

    private LocalVectorStore newStore(InMemoryVectorDocumentRepository repository, int dimension,
                                      QuantizationType quantization) {
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(STORE);
        definition.setDimension(dimension);
        definition.setMetric("COSINE");
        definition.setMaxCapacity(100000);
        definition.setQuantization(quantization);
        return new LocalVectorStore(definition);
    }

    /** 为内存 Store 登记水位基线（模拟 loadStore 的元数据落库，但使用可注入时钟） */
    private void establishBaseline(AbstractDocumentPersistence persistence,
                                   InMemoryVectorDocumentRepository repository,
                                   LocalVectorStore store,
                                   Instant baseline) {
        persistence.saveStoreMetadata(store);
        VectorStoreMetadata metadata = repository.findStoreMetadata(STORE).orElseThrow();
        metadata.setSyncWatermark(baseline);
        repository.saveStoreMetadata(metadata);
    }

    private VectorDocument doc(String id, float[] vector) {
        return new VectorDocument(id, vector, "text-" + id, null);
    }

    private VectorDocumentEntity entity(String docId, float[] vector) {
        return VectorDocumentEntity.float32(docId, "text-" + docId, null, vector);
    }

    private static final class TestPersistence extends AbstractDocumentPersistence {
        private TestPersistence(VectorDocumentRepository repository) {
            super(repository, new VectorLiteProperties());
        }
    }
}

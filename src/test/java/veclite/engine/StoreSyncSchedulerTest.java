package veclite.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreManager;
import veclite.config.VectorLiteProperties;
import veclite.model.QuantizationType;
import veclite.model.StorageType;
import veclite.model.VectorDocument;
import veclite.model.VectorStoreStats;
import veclite.persistence.AbstractDocumentPersistence;
import veclite.persistence.InMemoryVectorDocumentRepository;
import veclite.persistence.VectorDocumentRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 多节点增量同步端到端回归：两个 Client 共享同一真相源（内存仓储模拟），
 * A 节点写透后，B 节点无需全量 reload，经 syncStore / 调度器 runOnce 即收敛内存投影。
 */
class StoreSyncSchedulerTest {

    private static final String STORE = "shared_store";

    @Test
    @DisplayName("多节点收敛：对端写透的 upsert 与删除经增量同步应用到本节点内存")
    void incrementalSyncConvergesPeerWrites() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().setType(StorageType.POSTGRES);

        // 两个节点先后建立（此时真相源为空），各自经 loadStore 建立水位基线
        LocalVectorEngine engineA = new LocalVectorEngine(properties);
        LocalVectorEngine engineB = new LocalVectorEngine(properties);
        VectorEngineClientImpl nodeA = newClient(repository, properties, engineA);
        VectorEngineClientImpl nodeB = newClient(repository, properties, engineB);
        nodeA.createStore(STORE, definition());
        nodeB.createStore(STORE, definition());

        // A 写透两条
        nodeA.upsertBatch(STORE, List.of(
                new VectorDocument("d1", new float[]{1.0f, 0.0f}, "text-d1", null),
                new VectorDocument("d2", new float[]{0.0f, 1.0f}, "text-d2", null)));
        nodeB.syncStore(STORE);
        LocalVectorStore storeB = engineB.getStore(STORE);
        assertNotNull(storeB.getDocument("d1", false));
        assertNotNull(storeB.getDocument("d2", false));

        // A 删除 d1，B 增量同步后收敛
        nodeA.deleteByIds(STORE, List.of("d1"));
        nodeB.syncStore(STORE);
        assertNull(storeB.getDocument("d1", false), "peer tombstone must propagate to memory");
        assertEquals(1, storeB.getActiveCount());
    }

    @Test
    @DisplayName("调度器 runOnce：逐 Store 同步，单库失败不中断其余库")
    void runOnceSyncsEveryStoreAndToleratesFailures() {
        InMemoryVectorDocumentRepository repository = new InMemoryVectorDocumentRepository();
        VectorLiteProperties properties = new VectorLiteProperties();
        properties.getStorage().setType(StorageType.POSTGRES);

        LocalVectorEngine engineB = new LocalVectorEngine(properties);
        VectorEngineClientImpl nodeA = newClient(repository, properties, new LocalVectorEngine(properties));
        VectorEngineClientImpl nodeB = newClient(repository, properties, engineB);
        nodeA.createStore(STORE, definition());
        nodeB.createStore(STORE, definition());

        nodeA.upsertBatch(STORE, List.of(
                new VectorDocument("d1", new float[]{1.0f, 0.0f}, "text-d1", null)));

        // StoreManager 额外上报一个本节点未加载的库：syncStore 抛错必须被调度器吞掉
        VectorStoreManager managerWithMissingStore = new VectorStoreManager() {
            @Override public void createStore(String storeName, VectorStoreDefinition definition) { }
            @Override public boolean hasStore(String storeName) { return STORE.equals(storeName); }
            @Override public void dropStore(String storeName) { }
            @Override public VectorStoreStats stats(String storeName) { return nodeB.stats(STORE); }
            @Override public List<String> listStores() { return List.of(STORE, "not_loaded_here"); }
        };

        StoreSyncScheduler scheduler = new StoreSyncScheduler(nodeB, managerWithMissingStore, properties);
        scheduler.runOnce();

        assertNotNull(engineB.getStore(STORE).getDocument("d1", false),
                "healthy store must still be synced although another store failed");
    }

    @Test
    @DisplayName("调度器 start/stop 幂等且可重复调用")
    void startAndStopAreIdempotent() {
        StoreSyncScheduler scheduler = new StoreSyncScheduler(
                null, null, new VectorLiteProperties());
        scheduler.start();
        scheduler.start();
        scheduler.stop();
        scheduler.stop();
    }

    private VectorEngineClientImpl newClient(VectorDocumentRepository repository, VectorLiteProperties properties,
                                             LocalVectorEngine engine) {
        AbstractDocumentPersistence persistence = new TestPersistence(repository);
        return new VectorEngineClientImpl(engine, null, persistence, properties, null);
    }

    private VectorStoreDefinition definition() {
        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName(STORE);
        definition.setDimension(2);
        definition.setMetric("COSINE");
        definition.setMaxCapacity(100000);
        definition.setQuantization(QuantizationType.NONE);
        return definition;
    }

    private static final class TestPersistence extends AbstractDocumentPersistence {
        private TestPersistence(VectorDocumentRepository repository) {
            super(repository, new VectorLiteProperties());
        }
    }
}

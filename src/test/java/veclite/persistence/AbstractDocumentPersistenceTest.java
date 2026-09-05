package veclite.persistence;

import org.junit.jupiter.api.Test;
import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreMetadata;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorStore;
import veclite.model.StorageType;
import veclite.model.VectorDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractDocumentPersistenceTest {

    @Test
    void saveStoreWritesAndDeletesInBoundedBatches() {
        RecordingRepository repository = new RecordingRepository();
        repository.remoteIds.add("stale");
        AbstractDocumentPersistence persistence = new TestPersistence(repository);

        VectorStoreDefinition definition = new VectorStoreDefinition();
        definition.setStoreName("batch_store");
        definition.setDimension(2);
        definition.setMaxCapacity(2000);
        LocalVectorStore store = new LocalVectorStore(definition);
        for (int i = 0; i < 1501; i++) {
            store.upsert(new VectorDocument("doc-" + i, new float[]{i, i + 1}, null, null));
        }

        persistence.saveStore(store);

        assertEquals(1501, repository.upsertedCount);
        assertEquals(List.of(1000, 501), repository.upsertBatchSizes);
        assertTrue(repository.maxUpsertBatchSize <= 1000);
        assertEquals(List.of("stale"), repository.deletedIds);
    }

    private static final class TestPersistence extends AbstractDocumentPersistence {
        private TestPersistence(VectorDocumentRepository repository) {
            super(repository, StorageType.POSTGRES, new VectorLiteProperties());
        }
    }

    private static final class RecordingRepository implements VectorDocumentRepository {
        private final List<String> remoteIds = new ArrayList<>();
        private final List<Integer> upsertBatchSizes = new ArrayList<>();
        private final List<String> deletedIds = new ArrayList<>();
        private int upsertedCount;
        private int maxUpsertBatchSize;

        @Override public void ensureSchema() { }

        @Override
        public void upsertBatch(String storeName, List<VectorDocumentEntity> entities) {
            upsertBatchSizes.add(entities.size());
            upsertedCount += entities.size();
            maxUpsertBatchSize = Math.max(maxUpsertBatchSize, entities.size());
        }

        @Override
        public long deleteByIds(String storeName, List<String> documentIds) {
            deletedIds.addAll(documentIds);
            return documentIds.size();
        }

        @Override public long deleteAll(String storeName) { return 0; }
        @Override public Iterator<VectorDocumentEntity> scan(String storeName) { return Collections.emptyIterator(); }
        @Override public List<String> listDocumentIds(String storeName) { return remoteIds; }
        @Override public long count(String storeName) { return 0; }
        @Override public void saveStoreMetadata(VectorStoreMetadata metadata) { }
        @Override public Optional<VectorStoreMetadata> findStoreMetadata(String storeName) { return Optional.empty(); }
        @Override public List<VectorStoreMetadata> listStoreMetadata() { return Collections.emptyList(); }
        @Override public void deleteStoreMetadata(String storeName) { }
        @Override public void close() { }
    }
}

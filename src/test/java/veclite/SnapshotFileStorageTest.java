package veclite;

import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.engine.LocalVectorStore;
import veclite.model.VectorDocument;
import veclite.persistence.SnapshotFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

public class SnapshotFileStorageTest {

    @Test
    public void testSaveAndLoad(@TempDir Path tempDir) {
        VectorLiteProperties props = new VectorLiteProperties();
        props.getStorage().getSnapshotFile().setBasePath(tempDir.toString());

        SnapshotFileStorage storage = new SnapshotFileStorage(props);

        VectorStoreDefinition def = new VectorStoreDefinition();
        def.setStoreName("snap_store");
        def.setDimension(2);

        LocalVectorStore store = new LocalVectorStore(def);
        store.upsert(new VectorDocument("d1", new float[]{1.0f, 2.0f}, "text1", new HashMap<>()));
        store.upsert(new VectorDocument("d2", new float[]{3.0f, 4.0f}, "text2", new HashMap<>()));

        storage.flushSnapshot(store);

        LocalVectorStore loadedStore = new LocalVectorStore(def);
        storage.loadStore(loadedStore);

        assertEquals(2, loadedStore.getActiveCount());
    }
}

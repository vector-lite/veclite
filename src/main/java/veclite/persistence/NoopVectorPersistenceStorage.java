package veclite.persistence;

import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;

import java.util.Collections;
import java.util.List;

public class NoopVectorPersistenceStorage implements VectorPersistenceStorage {

    @Override
    public void loadStore(LocalVectorStore store) {
    }

    @Override
    public void deleteStore(String storeName) {
    }

    @Override
    public List<String> listStoreNames() {
        return Collections.emptyList();
    }

    @Override
    public VectorStoreDefinition loadStoreDefinition(String storeName) {
        return null;
    }
}

package veclite.persistence;

import veclite.engine.LocalVectorStore;

public interface VectorPersistenceStorage {

    void saveStore(LocalVectorStore store);

    void loadStore(LocalVectorStore store);

    void deleteStore(String storeName);
}

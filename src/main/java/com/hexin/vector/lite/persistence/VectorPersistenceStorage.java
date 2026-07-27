package com.hexin.vector.lite.persistence;

import com.hexin.vector.lite.engine.LocalVectorStore;

public interface VectorPersistenceStorage {

    void saveStore(LocalVectorStore store);

    void loadStore(LocalVectorStore store);

    void deleteStore(String storeName);
}

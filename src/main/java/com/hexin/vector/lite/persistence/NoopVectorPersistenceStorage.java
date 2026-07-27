package com.hexin.vector.lite.persistence;

import com.hexin.vector.lite.engine.LocalVectorStore;

public class NoopVectorPersistenceStorage implements VectorPersistenceStorage {

    @Override
    public void saveStore(LocalVectorStore store) {
    }

    @Override
    public void loadStore(LocalVectorStore store) {
    }

    @Override
    public void deleteStore(String storeName) {
    }
}

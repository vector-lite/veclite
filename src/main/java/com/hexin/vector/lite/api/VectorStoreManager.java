package com.hexin.vector.lite.api;

import com.hexin.vector.lite.model.VectorStoreStats;

import java.util.List;

public interface VectorStoreManager {

    void createStore(String storeName, VectorStoreDefinition definition);

    boolean hasStore(String storeName);

    void dropStore(String storeName);

    VectorStoreStats stats(String storeName);

    List<String> listStores();
}

package veclite.api;

import veclite.model.VectorStoreStats;

import java.util.List;

public interface VectorStoreManager {

    void createStore(String storeName, VectorStoreDefinition definition);

    boolean hasStore(String storeName);

    void dropStore(String storeName);

    VectorStoreStats stats(String storeName);

    List<String> listStores();
}

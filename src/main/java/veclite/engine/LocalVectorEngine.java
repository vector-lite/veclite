package veclite.engine;

import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreManager;
import veclite.config.VectorLiteProperties;
import veclite.model.VectorStoreStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalVectorEngine implements VectorStoreManager {

    private final VectorLiteProperties properties;
    private final Map<String, LocalVectorStore> stores = new ConcurrentHashMap<>();

    public LocalVectorEngine() {
        this(null);
    }

    public LocalVectorEngine(VectorLiteProperties properties) {
        this.properties = properties;
    }

    @Override
    public void createStore(String storeName, VectorStoreDefinition definition) {
        if (storeName == null || definition == null) {
            throw new IllegalArgumentException("Store name and definition must not be null");
        }
        definition.setStoreName(storeName);
        stores.putIfAbsent(storeName, new LocalVectorStore(definition, properties));
    }

    @Override
    public boolean hasStore(String storeName) {
        return storeName != null && stores.containsKey(storeName);
    }

    @Override
    public void dropStore(String storeName) {
        if (storeName != null) {
            stores.remove(storeName);
        }
    }

    @Override
    public VectorStoreStats stats(String storeName) {
        LocalVectorStore store = getStore(storeName);
        return store.getStats();
    }

    @Override
    public List<String> listStores() {
        return new ArrayList<>(stores.keySet());
    }

    public LocalVectorStore getStore(String storeName) {
        LocalVectorStore store = stores.get(storeName);
        if (store == null) {
            throw new IllegalArgumentException("Vector store not found: " + storeName);
        }
        return store;
    }
}

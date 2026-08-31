package veclite.persistence;

import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;

import java.util.List;

public interface VectorPersistenceStorage {

    void saveStore(LocalVectorStore store);

    void loadStore(LocalVectorStore store);

    void deleteStore(String storeName);

    List<String> listStoreNames();

    VectorStoreDefinition loadStoreDefinition(String storeName);
}

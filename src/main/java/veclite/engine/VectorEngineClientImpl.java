package veclite.engine;

import veclite.api.EmbeddingProvider;
import veclite.api.VectorEngineClient;
import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.model.*;
import veclite.persistence.VectorPersistenceStorage;

import java.util.List;

public class VectorEngineClientImpl implements VectorEngineClient {

    private final LocalVectorEngine localVectorEngine;
    private final EmbeddingProvider embeddingProvider;
    private final VectorPersistenceStorage persistence;
    private final VectorLiteProperties properties;

    public VectorEngineClientImpl(LocalVectorEngine localVectorEngine,
                                  EmbeddingProvider embeddingProvider,
                                  VectorPersistenceStorage persistence,
                                  VectorLiteProperties properties) {
        this.localVectorEngine = localVectorEngine;
        this.embeddingProvider = embeddingProvider;
        this.persistence = persistence;
        this.properties = properties;

        initStoresFromProperties();
    }

    private void initStoresFromProperties() {
        if (properties.getStores() != null) {
            properties.getStores().forEach((storeName, config) -> {
                VectorStoreDefinition definition = new VectorStoreDefinition();
                definition.setStoreName(storeName);
                definition.setDimension(config.getDimension());
                definition.setMetric(config.getMetric());
                definition.setMaxCapacity(config.getMaxCapacity());
                definition.setEmbeddingModel(config.getEmbeddingModel());
                definition.setQuantization(config.getQuantization());
                definition.setIndexedMetadataFields(config.getIndexedMetadataFields());
                localVectorEngine.createStore(storeName, definition);
            });
        }
    }

    @Override
    public void createStore(String storeName, VectorStoreDefinition definition) {
        localVectorEngine.createStore(storeName, definition);
    }

    @Override
    public void upsert(String storeName, VectorDocument document) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        store.upsert(document);
    }

    @Override
    public void upsertBatch(String storeName, List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        for (VectorDocument doc : documents) {
            store.upsert(doc);
        }
    }

    @Override
    public List<VectorSearchResult> searchByVector(VectorSearchRequest request) {
        if (request == null || request.getStoreName() == null) {
            throw new IllegalArgumentException("Search request and storeName must not be null");
        }
        LocalVectorStore store = localVectorEngine.getStore(request.getStoreName());
        return store.search(request);
    }

    @Override
    public List<VectorSearchResult> searchByText(VectorSearchRequest request) {
        if (request == null || request.getStoreName() == null || request.getQueryText() == null) {
            throw new IllegalArgumentException("Search request, storeName, and queryText must not be null");
        }
        LocalVectorStore store = localVectorEngine.getStore(request.getStoreName());
        String modelName = store.getDefinition().getEmbeddingModel();
        if (modelName == null) {
            modelName = properties.getEmbedding().getDefaultModel();
        }
        if (embeddingProvider == null || modelName == null) {
            throw new IllegalStateException("No EmbeddingProvider or embedding model configured for store: " + request.getStoreName());
        }

        List<Float> floatList = embeddingProvider.embed(modelName, null, request.getQueryText());
        float[] vector = new float[floatList.size()];
        for (int i = 0; i < floatList.size(); i++) {
            vector[i] = floatList.get(i);
        }
        request.setQueryVector(vector);
        return store.search(request);
    }

    @Override
    public List<VectorSearchResult> hybridSearch(VectorSearchRequest request) {
        if (request.getQueryVector() == null && request.getQueryText() != null) {
            return searchByText(request);
        }
        return searchByVector(request);
    }

    @Override
    public DeleteResult deleteByIds(String storeName, List<String> ids) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        return store.deleteByIds(ids);
    }

    @Override
    public DeleteResult deleteByFilter(String storeName, FilterExpression filter) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        return store.deleteByFilter(filter);
    }

    @Override
    public VectorStoreStats stats(String storeName) {
        return localVectorEngine.stats(storeName);
    }

    @Override
    public void refresh(String storeName) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        persistence.saveStore(store);
    }

    @Override
    public void reload(String storeName) {
        LocalVectorStore store = localVectorEngine.getStore(storeName);
        persistence.loadStore(store);
    }
}

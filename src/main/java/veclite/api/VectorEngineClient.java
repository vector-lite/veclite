package veclite.api;

import veclite.model.*;

import java.util.List;

public interface VectorEngineClient {

    void createStore(String storeName, VectorStoreDefinition definition);

    void upsert(String storeName, VectorDocument document);

    void upsertBatch(String storeName, List<VectorDocument> documents);

    List<VectorSearchResult> searchByVector(VectorSearchRequest request);

    List<VectorSearchResult> searchByText(VectorSearchRequest request);

    List<VectorSearchResult> hybridSearch(VectorSearchRequest request);

    DeleteResult deleteByIds(String storeName, List<String> ids);

    DeleteResult deleteByFilter(String storeName, FilterExpression filter);

    VectorDocumentPage listDocuments(String storeName, int page, int size, boolean includeVector);

    /**
     * 默认不返回 vector 字段的便捷方法。
     */
    default VectorDocumentPage listDocuments(String storeName, int page, int size) {
        return listDocuments(storeName, page, size, false);
    }

    VectorStoreStats stats(String storeName);

    void refresh(String storeName);

    void reload(String storeName);
}

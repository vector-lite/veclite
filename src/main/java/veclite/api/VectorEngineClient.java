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

    VectorDocumentPage listDocuments(String storeName, int page, int size);

    /**
     * 按 ID 查询单个文档（含向量、文本与元数据）。
     * 默认实现抛出 UnsupportedOperationException，由具体实现类覆写。
     */
    default VectorDocument getDocument(String storeName, String id) {
        throw new UnsupportedOperationException("getDocument is not supported by this client implementation");
    }

    VectorStoreStats stats(String storeName);

    /**
     * 重新从持久化真相源发现并装载尚未加载的存量 Store（如数据源补配后恢复被跳过的库）。
     * 默认空实现，保持既有实现类兼容。
     */
    default void rediscoverPersistedStores() {
    }

    void refresh(String storeName);

    void reload(String storeName);
}

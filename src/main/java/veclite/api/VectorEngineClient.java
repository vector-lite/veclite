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

    /**
     * 集合级对账：以内存为权威对真相源做一致性修复——补齐真相源缺失的文档、软删滞留行、
     * 同步元数据，返回对账 diff 明细（修复条数、样本 ID 与耗时）。
     * 不重写双方已一致的文档（写透路径下内存与真相源本就一致，这是运维修复工具而非周期任务）。
     * 默认实现抛出 UnsupportedOperationException，由文档型持久化的具体实现类覆写。
     */
    default ReconcileResult reconcileStore(String storeName) {
        throw new UnsupportedOperationException("reconcileStore requires a document-backed persistence backend");
    }

    /**
     * 全量重建：重置内存后从真相源整库装载，并建立增量同步水位基线。
     */
    void reload(String storeName);

    /**
     * 增量同步：按元数据水位从真相源拉取变更应用到内存（多节点定时收敛的轻量通道）。
     * 默认实现抛出 UnsupportedOperationException，由文档型持久化的具体实现类覆写。
     */
    default StoreSyncResult syncStore(String storeName) {
        throw new UnsupportedOperationException("syncStore requires a document-backed persistence backend");
    }
}

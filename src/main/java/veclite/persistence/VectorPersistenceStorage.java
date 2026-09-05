package veclite.persistence;

import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;

import java.util.List;

/**
 * 持久化存储端口。文档型后端（MongoDB/PostgreSQL）下真相源经写透与内存保持一致：
 * {@link #saveStore} 是运维触发的集合级对账（修复漂移，不重写已一致文档），
 * {@link #loadStore} 是全量重建（冷启动/修复/水位基线建立）。
 */
public interface VectorPersistenceStorage {

    void saveStore(LocalVectorStore store);

    void loadStore(LocalVectorStore store);

    void deleteStore(String storeName);

    List<String> listStoreNames();

    VectorStoreDefinition loadStoreDefinition(String storeName);
}

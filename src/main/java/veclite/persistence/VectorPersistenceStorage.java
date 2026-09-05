package veclite.persistence;

import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;

import java.util.List;

/**
 * 持久化存储端口。文档型后端（MongoDB/PostgreSQL）下真相源经写透与内存保持一致：
 * {@link #loadStore} 是全量重建（冷启动/修复/水位基线建立）。集合级对账是文档型
 * 真相源特有的能力，声明在 {@link DocumentBackedPersistence#reconcileStore}；
 * 本端口只保留各后端通用的装载/删除/发现语义。
 */
public interface VectorPersistenceStorage {

    void loadStore(LocalVectorStore store);

    void deleteStore(String storeName);

    List<String> listStoreNames();

    VectorStoreDefinition loadStoreDefinition(String storeName);
}

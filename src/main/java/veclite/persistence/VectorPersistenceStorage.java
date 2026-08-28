package veclite.persistence;

import veclite.api.VectorStoreDefinition;
import veclite.engine.LocalVectorStore;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public interface VectorPersistenceStorage {

    void saveStore(LocalVectorStore store);

    void loadStore(LocalVectorStore store);

    void deleteStore(String storeName);

    /**
     * 启动时枚举所有可加载的 store 名称。
     * <p>默认空实现（无持久化场景）。
     */
    default List<String> listStoreNames() {
        return Collections.emptyList();
    }

    /**
     * 加载指定 store 的定义（不加载向量数据）。
     * <p>用于启动时先拿到 dimension 等元信息再决定如何创建 LocalVectorStore。
     * <p>默认抛 {@link UnsupportedOperationException}，要求实现类显式支持。
     */
    default VectorStoreDefinition loadStoreDefinition(String storeName) {
        throw new UnsupportedOperationException(
                "loadStoreDefinition is not supported by " + getClass().getSimpleName());
    }

    /**
     * 分布式锁钩子（接口预留，集群化时实现）。
     * <p>单机版本：直接执行 action，无锁。
     * <p>未来集群版本：使用 Redis SETNX / ZK / K8s Lease 选主。
     */
    default <T> T withStoreLock(String storeName, Supplier<T> action) {
        return action.get();
    }

    /**
     * 带版本号的保存（接口预留，集群化时实现）。
     * <p>单机版本：忽略版本号直接保存。
     * <p>未来集群版本：CAS 写，防止覆盖其他 Pod 的更新。
     *
     * @param store           要保存的 store
     * @param expectedVersion 期望的当前版本号（null 表示不检查）
     */
    default void saveStoreWithVersion(LocalVectorStore store, String expectedVersion) {
        saveStore(store);
    }
}

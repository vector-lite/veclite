package veclite.persistence.meta;

import java.util.List;
import java.util.Optional;

/**
 * v2.4 hybrid persistence: 元数据仓储接口
 * 来自 design/v2.4/hybrid_persistence_design.md 第 3.1 节
 * 默认实现 PostgresMetadataRepository，可扩展 Mongo / MySQL / Local。
 */
public interface VectorMetadataRepository {

    /** 保存或更新元数据 */
    void save(VectorStoreMetadata metadata);

    /** 根据 storeName 查元数据 */
    Optional<VectorStoreMetadata> findByName(String storeName);

    /** 列出全部（启动发现用） */
    List<VectorStoreMetadata> listAll();

    /** 刷盘成功后，更新快照指针（PG ↔ OSS 对齐） */
    void updateSnapshotPointer(String storeName, String snapshotVersion, String ossPath, int activeCount);

    /** 删除元数据 */
    void deleteByName(String storeName);
}

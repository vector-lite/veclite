package veclite.persistence;

import veclite.api.VectorStoreMetadata;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * 向量文档数据源端口（存储无关）。
 * <p>
 * 声明文档与 Store 元数据的 CRUD 及全量扫描能力，不暴露任何具体存储（MongoDB/PostgreSQL）类型。
 * 这是 v2.5 设计稿 §3.4 预留的扩展点：未来支持 PostgreSQL 时新增
 * {@code PostgresVectorDocumentRepository} 实现并在自动装配中注册即可，引擎层与编排层零改动。
 */
public interface VectorDocumentRepository {

    /** Ensure the physical document resource for one Store exists. */
    default StorePersistenceHandle ensureStore(String storeName) {
        return new StorePersistenceHandle(storeName, storeName);
    }

    /** Resolve the physical document resource for one Store. */
    default StorePersistenceHandle handle(String storeName) {
        return new StorePersistenceHandle(storeName, storeName);
    }

    /** Drop the physical document resource for one Store. */
    default void dropStore(String storeName) {
        deleteAll(storeName);
    }

    /** 幂等初始化 schema（建集合/表与唯一索引），在仓储构造时调用 */
    void ensureSchema();

    /**
     * 批量写入/更新文档（按 storeName + docId 幂等 upsert）。
     * upsert 必须同时清除软删除标记（复活被 tombstone 的 docId）。
     * 批量导入必须使用底层批量 API（bulkWrite / JDBC batch），禁止逐条提交。
     */
    void upsertBatch(String storeName, List<VectorDocumentEntity> entities);

    /**
     * 软删除指定文档（标记 deleted + 刷新 updatedAt，保留 tombstone 行），
     * 返回实际标记的条数；ID 不存在时静默跳过。
     * 增量同步依赖 tombstone 让其他节点感知删除；物理清理由 {@link #purgeSoftDeletedBefore} 按保留期执行。
     */
    long deleteByIds(String storeName, List<String> documentIds);

    /** 删除指定 Store 的全部文档（dropStore 场景），返回删除条数 */
    long deleteAll(String storeName);

    /** 全量流式扫描指定 Store 的<b>未删除</b>文档（游标分批拉取，禁止一次性加载全量到内存） */
    Iterator<VectorDocumentEntity> scan(String storeName);

    /**
     * 增量流式扫描 updatedAt 严格晚于 watermark 的文档（<b>包含</b>软删除行，
     * 调用方据 deleted 标记分别应用 upsert/删除）。游标分批拉取，禁止一次性加载全量。
     */
    Iterator<VectorDocumentEntity> scanUpdatedSince(String storeName, Instant watermark);

    /** updatedAt 晚于 watermark 的文档数（含软删除行）；增量同步前的零成本快检 */
    long countUpdatedSince(String storeName, Instant watermark);

    /** 物理删除 updatedAt 早于 cutoff 的软删除行（tombstone 压缩），返回删除条数 */
    long purgeSoftDeletedBefore(String storeName, Instant cutoff);

    /** 拉取指定 Store 的全部<b>未删除</b>文档 ID（对账时用于找出真相源中的滞留行） */
    List<String> listDocumentIds(String storeName);

    long count(String storeName);

    void saveStoreMetadata(VectorStoreMetadata metadata);

    Optional<VectorStoreMetadata> findStoreMetadata(String storeName);

    /** 启动时发现全部已注册 Store（按 persistenceMode 决定各自从哪个后端装载） */
    List<VectorStoreMetadata> listStoreMetadata();

    void deleteStoreMetadata(String storeName);

    /** 释放底层连接资源 */
    void close();
}

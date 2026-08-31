package veclite.model;

/**
 * 持久化后端类型，由 {@code veclite.storage.type} 全局开关选择。
 * <p>
 * 业务代码零感知：{@code VectorPersistenceStorage} 端口的语义不变，仅换实现。
 */
public enum StorageType {

    /** 不做持久化，纯内存 */
    NOOP,

    /** 本地快照文件：手动或定时刷盘，见 {@code veclite.storage.snapshot-file} */
    SNAPSHOT_FILE,

    /** MongoDB 单一真相源：文档 text/metadata/向量写透落库（RPO=0），启动游标重建 */
    MONGODB,

    /** PostgreSQL 单一真相源：与 MONGODB 同构，文档表 + 元数据表，见 {@code veclite.storage.postgres} */
    POSTGRES
}

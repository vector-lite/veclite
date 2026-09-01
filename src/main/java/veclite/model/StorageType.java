package veclite.model;

/**
 * 持久化后端类型，由 {@code veclite.storage.type} 全局开关选择。
 * <p>
 * 业务代码零感知：{@code VectorPersistenceStorage} 端口的语义不变，仅换实现。
 */
public enum StorageType {

    /**
     * @deprecated 仅为旧版 SDK 源码兼容保留。生产配置必须使用数据库后端。
     */
    @Deprecated
    NOOP,

    /**
     * @deprecated 本地快照已退出生产路径，仅为读取旧配置和兼容旧测试保留。
     */
    @Deprecated
    SNAPSHOT_FILE,

    /** MongoDB 单一真相源：文档 text/metadata/向量写透落库（RPO=0），启动游标重建 */
    MONGODB,

    /** PostgreSQL 单一真相源：与 MONGODB 同构，文档表 + 元数据表，见 {@code veclite.storage.postgres} */
    POSTGRES
}

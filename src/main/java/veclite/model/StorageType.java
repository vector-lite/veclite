package veclite.model;

public enum StorageType {
    NOOP,
    SNAPSHOT_FILE,
    /** MongoDB 单一真相源持久化（v2.5）：文档 text/metadata/向量写透落库，启动游标重建 */
    MONGODB,
    /** 预留：DB 指针 + OSS 快照混合持久化（见 design/v2.4 hybrid_persistence_design.md），暂未实现 */
    HYBRID
}

# VecLite 集群化策略

本文描述当前实现（单进程内存检索 + 数据库持久化）的集群边界。集群编排、网关路由和副本发现不属于本仓库。

## 共享持久化

- 生产环境通过 `veclite.storage.type` 选择 `MONGODB` 或 `POSTGRES`。
- 文档、向量和 Store 元数据写入数据库；数据库是唯一真相源。
- `VectorEngineClientImpl` 启动时从数据库发现 Store，并按主键分页重建内存索引。
- upsert/delete 成功后立即写透数据库；`refresh` 仅用于整库对账，不依赖定时刷盘。

## 请求路由建议

- create、upsert、delete、refresh 等写请求应由网关按 `storeName` 固定路由到同一写节点。
- search、list、stats、reload 等读请求可路由到任一已完成数据库恢复的节点。
- 节点本地的 `LocalVectorStore` 是查询热路径，查询不会为每个请求访问数据库。
- 节点重启或副本落后时，通过 `reload` 从数据库重建；一致性边界由部署层的路由策略决定。

## 资源与并发边界

- 每个节点独立维护内存向量缓冲区、ID 索引、过滤位图和 Payload；数据库不承载在线 Top-K 计算。
- Store 内写操作由引擎串行协调，读操作使用已构建的内存索引。
- `payload.mode` 当前应保持 `MEMORY`；MMap 与本地快照属于历史兼容能力，不是生产持久化路径。
- 数据库连接、表/集合索引和批量扫描参数由 `VectorLiteProperties` 配置；部署时应为文档主键和 Store 元数据建立唯一索引。

## 不在当前实现范围内

- OSS 快照、FlushScheduler、ReplicaSyncScheduler、OrphanCleanScheduler 以及 PG advisory lock 均不是当前代码提供的组件。
- 跨节点自动复制、写入主从切换和网关 hash 路由需要在基础设施层实现。

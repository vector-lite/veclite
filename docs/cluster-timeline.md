# Veclite 集群完整时序图

> 单图覆盖：createStore / upsert / search / listDocuments / deleteByIds / dropStore 全部接口的集群链路。
> 复制到支持 Mermaid 渲染的 Markdown 查看器（Notion / Confluence / GitHub / VSCode）即可看到。

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端
    participant GW as 网关
    participant M as Master Pod
    participant Mem as 内存<br/>LocalVectorStore
    participant Disk as 本地 mmap
    participant Sched as FlushScheduler
    participant OSS as OSS
    participant PG as PostgreSQL
    participant R as Replica Pod
    participant RMem as 内存<br/>(replica)

    Note over FE,RMem: 1️⃣ 创建向量库 createStore
    FE->>GW: POST /stores/{name}
    GW->>M: 路由
    M->>PG: findByName(name)
    PG-->>M: empty
    M->>PG: save(metadata)
    PG-->>M: OK
    M->>Mem: hash 构造 LocalVectorStore
    M-->>FE: SUCCESS
    Note right of M: 异步：replica 拉空 snapshot

    Note over FE,RMem: 2️⃣ 写入文档 upsert（高频）
    FE->>GW: POST /stores/{name}/documents
    GW->>M: 路由
    M->>Mem: synchronized 写 IdOffsetIndex + SQ8
    M->>Disk: PayloadStorage.put 落 mmap
    M-->>FE: SUCCESS
    Note right of M: OSS / PG 不参与 upsert 实时路径

    Note over FE,RMem: 3️⃣ 定时刷盘（30s 一次）
    Sched->>M: flushAll
    M->>Disk: 打 snapshot 文件
    M->>OSS: PutObject vectors.bin + documents.jsonl
    OSS-->>M: OK
    M->>PG: updateSnapshotPointer<br/>(version, ossPath, count)
    Note right of M: PG 写指针让 replica 感知

    Note over FE,RMem: 4️⃣ replica 同步
    R->>PG: 查 latestSnapshotVersion
    PG-->>R: v_xxx（新版本）
    R->>R: 比对本地（旧版本）
    R->>OSS: GetObject snapshot
    OSS-->>R: vectors.bin + documents.jsonl
    R->>Disk: 写本地 mmap
    R->>RMem: LocalVectorStore.reload()
    Note right of R: replica 内存追上 master

    Note over FE,RMem: 5️⃣ 查询 search / list（读路径）
    FE->>GW: POST /stores/{name}/search/text
    GW->>R: 路由到任一 replica
    R->>RMem: LocalVectorStore.search<br/>(内存打分 + filter + topK)
    R-->>FE: topK 结果
    Note right of R: search 主路径只走内存<br/>不查 PG / 不读 OSS

    Note over FE,RMem: 6️⃣ 删除文档 deleteByIds
    FE->>GW: DELETE /stores/{name}/documents
    GW->>M: 路由
    M->>Mem: synchronized 软删位图 mark + 元数据位图清理
    M-->>FE: SUCCESS
    Note right of M: 物理回收等 compaction

    Note over FE,RMem: 7️⃣ 删除向量库 dropStore
    FE->>GW: DELETE /stores/{name}
    GW->>M: 路由
    M->>Mem: ConcurrentHashMap.remove
    M->>PG: deleteByName(name)
    M-->>FE: SUCCESS
    Note right of M: OSS / 本地磁盘文件保留<br/>由 OrphanCleanScheduler 兜底清理
```

---

## 关键观察

| 接口 | 实时路径走哪些 | 异步路径走哪些 | 集群化需要改 |
|---|---|---|---|
| createStore | 内存 + PG | — | 加 master 校验 + PG 预查 + 失败回滚 |
| upsert | 内存 + 本地 mmap | OSS + PG（30s） | 加 master 校验 + replica 同步机制 |
| search | 内存 | — | 不需要改（replica 内存有数据就查） |
| listDocuments | 内存 | — | 不需要改 |
| deleteByIds | 内存 | OSS + PG（30s） | 加 master 校验 |
| dropStore | 内存 + PG | — | 加 master 校验 + 孤儿清理 |

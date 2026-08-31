# Veclite 整体架构图

> 三张图：单 Pod 架构图、集群（3 master + 6 replica）架构图、upsert 集群时序图。
> 复制到支持 Mermaid 渲染的 Markdown 查看器（Notion / Confluence / GitHub / VSCode）即可看到。

---

## 图 1：单 Pod 整体架构

```mermaid
flowchart TB
    subgraph Frontend["前端层"]
        UI["管理 UI<br/>(frontend/)"]
    end

    subgraph Veclite["Veclite 进程（单 Pod 1C1G）"]
        direction TB

        subgraph WebLayer["Web 层 (web/)"]
            Controller["VectorLiteDebugController<br/>(REST API)"]
        end

        subgraph EngineLayer["引擎层 (engine/)"]
            EngineClient["VectorEngineClientImpl<br/>(业务门面)"]
            LocalEngine["LocalVectorEngine<br/>(ConcurrentHashMap)"]
        end

        subgraph StoreLayer["Store 层 (engine/LocalVectorStore)"]
            IdIndex["IdOffsetIndex"]
            SQ8["OffHeapSQ8Buffer<br/>(堆外向量)"]
            MetaIdx["MetadataFilterIndex<br/>(位图过滤)"]
            Payload["PayloadStorage<br/>(text + metadata)"]
            DelBit["DeletedBitSet<br/>(软删)"]
        end

        subgraph Embed["Embedding 层"]
            EmbedSvc["EmbeddingService"]
            HttpEmb["HttpEmbeddingProvider<br/>(DashScope / OpenAI)"]
        end

        subgraph Persist["持久化层 (persistence/)"]
            FlushSched["FlushScheduler<br/>(30s 定时)"]
            OssStorage["OssSnapshotStorage<br/>(本地 + OSS)"]
        end
    end

    subgraph External["外部存储"]
        PG[("PostgreSQL<br/>veclite_store_meta<br/>(store 元数据 + 快照指针)")]
        OSS[("OSS<br/>阿里云对象存储<br/>(snapshot 文件)")]
        LocalDisk["本地磁盘<br/>./data/vec/{storeName}/<br/>(mmap payload)"]
    end

    UI -->|HTTP| Controller
    Controller --> EngineClient
    EngineClient --> LocalEngine
    LocalEngine --> StoreLayer
    EngineClient -->|自动 embed| EmbedSvc
    EmbedSvc --> HttpEmb

    LocalEngine -.->|createStore<br/>dropStore| PG
    EngineClient -->|refresh / upsert<br/>触发刷盘| FlushSched
    FlushSched --> OssStorage
    OssStorage -->|写向量+payload| LocalDisk
    OssStorage -->|上传 snapshot| OSS
    FlushSched -.->|更新 latestSnapshotVersion| PG

    style PG fill:#e1f5e1,stroke:#2d6a2d
    style OSS fill:#fff3e0,stroke:#e67e22
    style LocalDisk fill:#f3e5f5,stroke:#7d3c98
    style UI fill:#e8f4f8,stroke:#4a90d9
```

---

## 图 2：K8s 集群架构（3 master + 6 replica）

```mermaid
flowchart TB
    subgraph Gateway["网关层（Service Mesh / Ingress）"]
        GW["API Gateway<br/>按 storeName hash<br/>路由到 master / 任意 replica"]
    end

    subgraph Frontend["前端层"]
        UI["管理 UI<br/>(frontend/)"]
        Harness["Agent Harness<br/>(业务方)"]
    end

    subgraph MasterPods["Master Pods (3 个 — 可写)"]
        M1["Master-1<br/>veclite pod<br/>管 store A,B"]
        M2["Master-2<br/>veclite pod<br/>管 store C,D"]
        M3["Master-3<br/>veclite pod<br/>管 store E~J"]
    end

    subgraph ReplicaPods["Replica Pods (6 个 — 只读)"]
        R1["Replica-1"]
        R2["Replica-2"]
        R3["Replica-3"]
        R4["Replica-4"]
        R5["Replica-5"]
        R6["Replica-6"]
    end

    subgraph SharedInfra["共享基础设施（集群唯一）"]
        PG[("PostgreSQL<br/>veclite_store_meta<br/>(9 pod 共享)")]
        OSS[("OSS<br/>阿里云对象存储<br/>(snapshot 仓库)")]
    end

    subgraph LocalCache["每 Pod 本地缓存（9 份）"]
        L1["本地 mmap 缓存<br/>./data/vec/{storeName}/"]
        L2["本地 mmap 缓存"]
        L3["本地 mmap 缓存"]
        L4["本地 mmap 缓存"]
        L5["本地 mmap 缓存"]
        L6["本地 mmap 缓存"]
        L7["本地 mmap 缓存"]
        L8["本地 mmap 缓存"]
        L9["本地 mmap 缓存"]
    end

    UI --> GW
    Harness --> GW

    GW -->|写 createStore/upsert/delete<br/>按 storeName 路由| M1
    GW -->|写| M2
    GW -->|写| M3

    GW -->|读 search/list/stats<br/>负载均衡| R1
    GW -->|读| R2
    GW -->|读| R3
    GW -->|读| R4
    GW -->|读| R5
    GW -->|读| R6

    M1 -.->|写元数据 + 指针| PG
    M2 -.->|写元数据 + 指针| PG
    M3 -.->|写元数据 + 指针| PG

    M1 -->|Push snapshot| OSS
    M2 -->|Push snapshot| OSS
    M3 -->|Push snapshot| OSS

    R1 -.->|查 version| PG
    R2 -.->|查 version| PG
    R3 -.->|查 version| PG
    R4 -.->|查 version| PG
    R5 -.->|查 version| PG
    R6 -.->|查 version| PG

    R1 -->|拉 snapshot| OSS
    R2 -->|拉 snapshot| OSS
    R3 -->|拉 snapshot| OSS
    R4 -->|拉 snapshot| OSS
    R5 -->|拉 snapshot| OSS
    R6 -->|拉 snapshot| OSS

    M1 --- L1
    M2 --- L2
    M3 --- L3
    R1 --- L4
    R2 --- L5
    R3 --- L6
    R4 --- L7
    R5 --- L8
    R6 --- L9

    style PG fill:#e1f5e1,stroke:#2d6a2d,stroke-width:2px
    style OSS fill:#fff3e0,stroke:#e67e22,stroke-width:2px
    style M1 fill:#ffe4e1,stroke:#c0392b
    style M2 fill:#ffe4e1,stroke:#c0392b
    style M3 fill:#ffe4e1,stroke:#c0392b
    style R1 fill:#e8f4f8,stroke:#4a90d9
    style R2 fill:#e8f4f8,stroke:#4a90d9
    style R3 fill:#e8f4f8,stroke:#4a90d9
    style R4 fill:#e8f4f8,stroke:#4a90d9
    style R5 fill:#e8f4f8,stroke:#4a90d9
    style R6 fill:#e8f4f8,stroke:#4a90d9
    style GW fill:#fff8dc,stroke:#daa520,stroke-width:2px
```

---

## 图 3：upsert 集群时序图（master 写 + replica 同步）

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端 / Agent Harness
    participant GW as 网关
    participant M as Master Pod
    participant Mem as 内存<br/>(LocalVectorStore)
    participant Disk as 本地 mmap<br/>(磁盘)
    participant Sched as FlushScheduler<br/>(30s 定时)
    participant OSS as OSS
    participant PG as PostgreSQL
    participant R1 as Replica-1
    participant R2 as Replica-2
    participant R3 as Replica-3

    Note over FE,R3: 一次 upsert 的完整集群链路

    rect rgb(255, 245, 230)
    Note over FE,Mem: 主路径：写内存 + 写磁盘
    FE->>GW: POST /stores/{name}/documents
    GW->>M: 按 storeName 路由到 master
    M->>M: synchronized 临界区
    M->>Mem: 写 IdOffsetIndex + OffHeapSQ8Buffer
    M->>Disk: PayloadStorage.put 落 mmap
    M-->>GW: 返回 SUCCESS
    GW-->>FE: 200 OK
    end

    rect rgb(230, 245, 255)
    Note over Sched,PG: 异步路径：刷 OSS + 写 PG 指针
    Sched->>M: 30s 触发 flushAll
    M->>M: saveStore 本地 snapshot
    M->>OSS: 上传 vectors.bin + documents.jsonl
    OSS-->>M: 上传成功
    M->>PG: updateSnapshotPointer<br/>(version, ossPath, activeCount)
    end

    rect rgb(230, 255, 230)
    Note over R1,R3: replica 同步：监听 PG → 拉 OSS → reload
    R1->>PG: 查 latestSnapshotVersion
    PG-->>R1: v_1724683800000
    R1->>R1: 比对本地版本（旧）
    R1->>OSS: GetObject snapshot
    OSS-->>R1: vectors.bin + documents.jsonl
    R1->>R1: 写本地 mmap + LocalVectorStore.reload()

    par 并行同步
        R2->>PG: 查 version
        R2->>OSS: 拉 snapshot
        R2->>R2: reload
    and
        R3->>PG: 查 version
        R3->>OSS: 拉 snapshot
        R3->>R3: reload
    end
    end

    Note over R1,R3: 最终一致：replica 内存与 master 差 ≤ 30s
```

---

## 图 4：createStore 集群时序图（含并发一致性）

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端
    participant GW as 网关
    participant M as Master Pod
    participant PG as PostgreSQL
    participant Mem as 内存 hash
    participant R as Replica Pods

    FE->>GW: POST /stores/{name}
    GW->>M: 按 storeName 路由

    rect rgb(255, 245, 230)
    Note over M,PG: 步骤 1：PG 预查（避免 hash + PG 不一致）
    M->>PG: findByName(name)
    PG-->>M: empty（不存在）
    end

    rect rgb(230, 245, 255)
    Note over M,Mem: 步骤 2：先 PG 后内存
    M->>PG: save(metadata)<br/>(storeName, dim, model, quant...)
    PG-->>M: OK
    M->>Mem: ConcurrentHashMap.compute<br/>构造 LocalVectorStore
    Mem-->>M: store 注册成功
    end

    M-->>GW: 200 SUCCESS
    GW-->>FE: 返回

    rect rgb(230, 255, 230)
    Note over R: 步骤 3：replica 感知（异步）
    R->>PG: listAll()
    PG-->>R: 含新 store
    R->>M: HTTP 拉空 snapshot（或者从 OSS）
    R->>R: 本地 mmap + reload
    end

    Note over M,R: 并发兜底：PG PRIMARY KEY 冲突 → 幂等返回<br/>失败回滚：PG 失败 → 不进 hash
```

---

## 图 5：search 集群时序图（按文本 / 按向量）

> 检索是 replica 节点的**主战场**：所有读路径（按向量 / 按文本）都路由到任意副本；按文本检索时副本会**独立调 Embedding 服务**（不依赖 master）。

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端 / Agent Harness
    participant GW as 网关
    participant R as Replica Pod
    participant Embed as EmbeddingService
    participant Mem as 内存<br/>(LocalVectorStore)
    participant MetaIdx as MetadataFilterIndex<br/>(位图)
    participant SQ8 as OffHeapSQ8Buffer<br/>(堆外向量)
    participant Payload as PayloadStorage

    Note over FE,Payload: 检索路径：read-only 走任一副本，不阻塞 master

    FE->>GW: POST /stores/{name}/search/text<br/>(queryText, topK, filter)
    GW->>R: 负载均衡到任一副本

    alt 按文本（search/text）
        R->>R: 读 store 期望的 embeddingModel
        R->>Embed: embed(model, text, targetDim)
        Embed->>Embed: 调 DashScope / OpenAI
        Embed-->>R: float[] 1024 维
    else 按向量（search/vector）
        Note over R: 直接用请求体里的 queryVector，跳过 Embed
    end

    rect rgb(230, 245, 255)
    Note over R,MetaIdx: 步骤 A：元数据位图过滤
    R->>MetaIdx: filter(metadata filter expr)
    MetaIdx-->>R: 候选 ID 位图
    end

    rect rgb(255, 245, 230)
    Note over R,SQ8: 步骤 B：堆外向量 TopK
    R->>SQ8: 计算距离（COSINE / L2 / DOT_PRODUCT）
    SQ8-->>R: TopK 候选 ID + score
    end

    rect rgb(230, 255, 230)
    Note over R,Payload: 步骤 C：补 payload（text + metadata）
    R->>Payload: batch get by ids
    Payload-->>R: text + metadata
    end

    R-->>GW: List<VectorSearchResult>
    GW-->>FE: 200 OK
```

**关键点**：
- 按文本检索时，replica 节点自己调 Embedding，不绕 master（`engine/VectorEngineClientImpl.java:226`）
- 检索是**纯读**：不会触发 FlushScheduler，不写 PG / OSS
- metadata 过滤走位图（`MetadataFilterIndex`），向量距离走堆外 SQ8 缓冲，两步可独立优化

---

## 图 6：delete 集群时序图（replica 拒绝写）

> 删除走**主路径同步删 + 异步落盘**，与 upsert 链路高度相似。replica 节点**直接 403**。

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端
    participant GW as 网关
    participant M as Master Pod
    participant R as Replica Pod
    participant Mem as 内存<br/>(LocalVectorStore)
    participant DelBit as DeletedBitSet<br/>(软删)
    participant Payload as PayloadStorage
    participant Sched as FlushScheduler
    participant OSS as OSS
    participant PG as PostgreSQL

    Note over FE,OSS: 一次 deleteByIds 的完整集群链路

    rect rgb(255, 245, 230)
    Note over GW,R: 步骤 0：路由到 master（replica 直接 403）
    FE->>GW: DELETE /stores/{name}/documents<br/>body: ["id-1","id-2"]
    GW->>R: 负载均衡命中 replica
    R-->>GW: 403 REJECTED<br/>(replica node rejects write)
    GW-->>FE: 403
    Note over FE: 客户端重试 / 由网关按 storeName 强制改路由到 master
    end

    rect rgb(230, 245, 255)
    Note over FE,M: 步骤 1：客户端改路由到 master
    FE->>GW: DELETE /stores/{name}/documents
    GW->>M: 按 storeName 路由到 master
    M->>M: synchronized 临界区
    M->>DelBit: set(ids) — 软删
    M->>Mem: 移除 IdOffsetIndex 中对应 offset
    M->>Payload: 删 mmap 段（同步）
    M-->>GW: DeleteResult{ deleted: 2 }
    GW-->>FE: 200 OK
    end

    rect rgb(230, 255, 230)
    Note over M,PG: 步骤 2：异步刷盘（同 upsert）
    Sched->>M: 30s 触发 flushAll
    M->>M: saveStore 本地 snapshot
    M->>OSS: 上传 vectors.bin + documents.jsonl<br/>(已不含被删的 doc)
    M->>PG: updateSnapshotPointer(version, ossPath, activeCount)
    end

    rect rgb(245, 245, 245)
    Note over R,OSS: 步骤 3：replica 跟随 master（与 upsert 时序相同）
    R->>PG: 查 latestSnapshotVersion
    R->>OSS: 拉新 snapshot
    R->>R: reload — 内存与磁盘同步收敛
    end
```

**关键点**：
- 删除 = 软删位图（`DeletedBitSet`）+ 索引摘除 + payload 删除三步，**全在 synchronized 临界区**（`engine/LocalVectorStore.java:603`）
- replica 拒绝任何写：`web/VectorLiteDebugController.java:42-48` 的 `rejectIfReplica()` 在 upsert / delete / createStore / refresh 4 个写接口前都拦一道
- 集群最终一致时间窗 = `FlushScheduler 周期`（默认 30s）

---

## 图 7：listDocuments 集群时序图（管理型读）

> 文档列表是**管理 / 调试用**接口，不走网关的检索路径，可命中任一副本。

```mermaid
sequenceDiagram
    autonumber
    participant FE as 前端
    participant GW as 网关
    participant R as Replica Pod<br/>(或 Master)
    participant Store as LocalVectorStore
    participant IdIdx as IdOffsetIndex
    participant DelBit as DeletedBitSet
    participant Payload as PayloadStorage

    FE->>GW: GET /stores/{name}/documents?page=0&size=20
    GW->>R: 任意副本

    R->>Store: listDocuments(page+1, size, includeVector=false)<br/>(VectorEngineClientImpl.java:265-275)
    Store->>Store: 归一化 page>=1, size=20
    Store->>IdIdx: 取 size 个 id
    IdIdx-->>Store: ids[]
    Store->>DelBit: 跳过置位（跳过已删）
    Store->>Payload: batch get by ids
    Payload-->>Store: VectorDocument{items}
    Store-->>R: VectorDocumentPage{items, page, size, total}
    R-->>GW: JSON: { items, page, size, total }
    GW-->>FE: 200 OK
```

**关键点**：
- 返回结构是**自定义 `VectorDocumentPage`**，字段是 `items` + `total`（`model/VectorDocumentPage.java:13-15`），**不是** Spring Data 的 `content` + `totalElements`——前端解析要按 `items` 取
- 默认 `includeVector=false`（`VectorEngineClientImpl.java:273`），避免 1024 维向量回传压垮管理 UI
- 不走位图检索路径，跳过 `MetadataFilterIndex`

---

## 图 8：refresh / reload 集群时序图（运维向）

> 这两个是**手动运维接口**，不走主路径，主要用于故障恢复 / 强制刷盘。

```mermaid
sequenceDiagram
    autonumber
    participant Ops as 运维 / 管理 UI
    participant M as Master Pod
    participant R as Replica Pod
    participant Persist as Persistence<br/>(OssSnapshotStorage)
    participant Disk as 本地 mmap
    participant OSS as OSS
    participant PG as PostgreSQL

    rect rgb(255, 245, 230)
    Note over Ops,PG: 场景 A：refresh（master 手动刷盘）
    Ops->>M: POST /stores/{name}/refresh
    M->>Persist: saveStore(store)<br/>(立即触发，不等 30s)
    Persist->>Disk: 写 vectors.bin + documents.jsonl
    Persist->>OSS: 上传 snapshot
    OSS-->>Persist: OK
    Persist->>PG: updateSnapshotPointer
    Persist-->>M: SUCCESS
    M-->>Ops: 200 OK
    end

    rect rgb(230, 245, 255)
    Note over Ops,R: 场景 B：reload（replica 强制重载）
    Ops->>R: POST /stores/{name}/reload
    R->>Persist: loadStore(store)<br/>(从本地 mmap 读，不走 OSS)
    Persist->>Disk: 读 vectors.bin + documents.jsonl
    Disk-->>Persist: bytes
    Persist->>R: 重建 LocalVectorStore
    R-->>Ops: 200 OK
    end

    Note over Ops,PG: 适用：本地 mmap 损坏、Pod 重启后内存空、强制刷盘绕过 30s 周期
```

**关键点**：
- `refresh` 走**主路径 + OSS + PG**（`VectorEngineClientImpl.java:306-309`），与 FlushScheduler 等价但**不等 30s**
- `reload` 只读**本地 mmap**（`VectorEngineClientImpl.java:315-318`），**不走 OSS / PG**——如果本地 mmap 损坏，reload 拿不到数据，得人工干预
- `refresh` / `reload` 这两个接口**replica 也能调**（controller 没加 `rejectIfReplica`）：reload 是只读本地，refresh 在 replica 上等同 noop（replica 不负责刷盘）

---

## 图 9：完整接口表 + replica 读写限制矩阵

> 解决"哪些接口能 / 不能给 replica 调"+"master 唯一 vs 任一副本可读"的查询痛点。

### 9.1 接口总表

| # | 方法 | 路径 | 写 / 读 | 角色限制 | 落盘 | 备注 |
|---|------|------|---------|----------|------|------|
| 1 | GET | `/stores` | 读 | 任一副本 | 否 | 仅返回 store name 列表 |
| 2 | GET | `/stores/_details` | 读 | 任一副本 | 否 | 含 dimension / metric / docCount / storageSource |
| 3 | POST | `/stores/{name}` | 写 | master only | 立即写 PG | createStore；并发兜底靠 PG PRIMARY KEY |
| 4 | GET | `/stores/{name}/stats` | 读 | 任一副本 | 否 | |
| 5 | DELETE | `/stores/{name}` | 写 | master only | 立即写 PG | dropStore；OSS 残留由 OrphanCleanScheduler 兜底 |
| 6 | POST | `/stores/{name}/documents` | 写 | master only | 立即落 mmap；30s 推 OSS | upsert |
| 7 | GET | `/stores/{name}/documents` | 读 | 任一副本 | 否 | 分页；返回 `{items, total}` |
| 8 | POST | `/stores/{name}/search/vector` | 读 | 任一副本 | 否 | 按向量 TopK |
| 9 | POST | `/stores/{name}/search/text` | 读 | 任一副本 | 否 | 按文本 + 自动 embed（replica 独立调 Embedding） |
| 10 | DELETE | `/stores/{name}/documents` | 写 | master only | 立即落 mmap；30s 推 OSS | deleteByIds |
| 11 | POST | `/stores/{name}/reload` | 运维 | master / replica 都可 | 否 | 只读本地 mmap |
| 12 | POST | `/stores/{name}/refresh` | 运维 | master 生效；replica noop | 立即刷盘 | 绕过 30s FlushScheduler |
| 13 | GET | `/_debug/health` | 探活 | 任一节点 | 否 | Spring Bean 注入状态 |
| 14 | GET | `/_debug/embed-test` | 探活 | 任一节点 | 否 | 直接测 EmbeddingProvider |
| 15 | GET | `/_debug/embed-service-test` | 探活 | 任一节点 | 否 | 测 EmbeddingService.embedTexts |
| 16 | GET | `/` 或 `/ui` | UI | 任一节点 | 否 | 转发到 `index.html`（Vue Console 入口） |

### 9.2 Replica 读写限制矩阵

| 写接口 | master | replica | 失败原因 / 行为 |
|--------|--------|---------|----------------|
| POST /stores/{name} | ✅ | ❌ 403 | `rejectIfReplica()` 拦截；replica 不持有 store 写入权 |
| POST /stores/{name}/documents | ✅ | ❌ 403 | 同上 |
| DELETE /stores/{name}/documents | ✅ | ❌ 403 | 同上 |
| DELETE /stores/{name} | ✅ | ❌ 403 | 同上 |
| POST /stores/{name}/refresh | ✅ | ⚠️ 200 (noop) | controller 未拦，但 replica 上等同 noop（不负责刷盘） |
| POST /stores/{name}/reload | ✅ | ✅ | reload 只读本地 mmap，replica 有自己的 mmap |
| 所有 GET / search | ✅ | ✅ | 检索 / 列表 / stats 都可走副本 |

### 9.3 关键代码定位

| 关注点 | 位置 |
|--------|------|
| 写接口拦 replica | `web/VectorLiteDebugController.java:42-48` `rejectIfReplica()` |
| API 路由基路径 | `web/VectorLiteDebugController.java:21` `${veclite.web.base-path:/veclite/api/v1}` |
| 分页返回结构 | `model/VectorDocumentPage.java:13-15` 字段是 `items` + `total`（**不是** Spring Data 的 `content` + `totalElements`） |
| 按文本检索（replica 独立 embed） | `engine/VectorEngineClientImpl.java:226` |
| 软删同步临界区 | `engine/LocalVectorStore.java:603` `synchronized deleteByIds` |
| 列表分页归一化 | `engine/VectorEngineClientImpl.java:271-274`（page 从 1 起，size 默认 20） |
| Reload 只读本地 | `engine/VectorEngineClientImpl.java:315-318` |
| Refresh 立即刷盘 | `engine/VectorEngineClientImpl.java:306-309` |

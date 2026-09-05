# VecLite 核心流程图

> 三张图覆盖三大关键操作：**写入流程**、**检索流程**、**持久化流程**。
> 基于 `engine/LocalVectorStore.java` 和 `engine/VectorEngineClientImpl.java` 逆向绘制。

---

## 图 1：写入流程（upsert 单条文档）

```mermaid
flowchart TD
    A["调用方<br/>POST /stores/{name}/documents"] --> B["VectorLiteDebugController.upsert"]
    B --> C["VectorEngineClientImpl.upsert"]
    C --> D["LocalVectorEngine.getStore<br/>（获取 Store 实例）"]
    D --> E["LocalVectorStore.upsert"]

    E --> F{"ID 已存在？<br/>IdOffsetIndex.contains"}
    F -- "是（更新）" --> G1["复用原 offset<br/>（不追加、不增加计数）"]
    F -- "否（新增）" --> G2["vectorBuffer.append<br/>（追加到平铺缓冲区）"]
    G2 --> H["IdOffsetIndex.put<br/>（注册 ID → 新 offset）"]
    G1 --> I["写入 payload"]
    H --> I

    I --> J["PayloadStorage.put<br/>（text + metadata）"]
    I --> K["MetadataFilterIndex.put<br/>（按 indexedMetadataFields<br/>建倒排位图）"]
    I --> L["DeletedBitSet.clear<br/>（复活已软删的同 ID）"]

    J --> Z["返回 SUCCESS"]
    K --> Z
    L --> Z

    Z --> M["DocumentBackedPersistence.upsertDocuments"]
    M --> N["MongoDB / PostgreSQL 文档表<br/>与元数据表写透"]
```

### 关键不变量

| 不变量 | 保证机制 |
|---|---|
| 同一个 ID 不会产生两个 offset | 写入前查 `IdOffsetIndex`，已存在则复用 |
| offset 不会指向未写入的位置 | 先 `vectorBuffer.append` 再 `IdOffsetIndex.put`（顺序保证） |
| 软删除的同 ID 重新 upsert 会"复活" | `DeletedBitSet.clear(id)` 翻位 |
| 软删除的同 ID 重新 upsert **offset 不变** | 更新分支走"复用原 offset"，不追加 |

---

## 图 2：检索流程（search）

```mermaid
flowchart TD
    A["调用方<br/>POST /stores/{name}/search/vector<br/>或 /search/text"] --> B["VectorLiteDebugController<br/>searchByVector / searchByText"]
    B --> C{"searchByText?"}
    C -- "是" --> D1["embeddingProvider.embed<br/>→ HTTP 调外部 Embedding"]
    D1 --> D2["拿到 float[] 后<br/>setQueryVector"]
    C -- "否" --> D3["request 已含 queryVector"]
    D2 --> E
    D3 --> E

    E["LocalVectorStore.search"] --> F["维度校验<br/>queryVector.length == dimension"]
    F --> G["应用 filter（若有）<br/>MetadataFilterIndex.evaluate<br/>→ BitSet matchingBitSet"]

    G --> H["获取 calibration snapshot<br/>（前 N 条向量冻结的 SQ8 校准）"]
    H --> I{"向量化 / 量化模式？"}
    I -- "Float32 原生" --> J1["vectorBuffer.calculateScore<br/>（直接读 float[]）"]
    I -- "SQ8 压缩" --> J2["SQ8Quantizer.calculate<br/>（按度量 + per-dim min/scale<br/>反量化算距离）"]

    J1 --> K["遍历 BitSet 候选"]
    J2 --> K

    K --> L["Top-K 堆<br/>PriorityQueue<TopKCandidate><br/>（按 score 排序）"]
    L --> M["过滤 minScore 阈值"]
    M --> N["TopK 收满后<br/>堆顶即最差候选<br/>新候选 < 堆顶则替换"]
    N --> O["buildResults<br/>→ List<VectorSearchResult>"]
    O --> P["返回（score + id + text + metadata）"]
```

### Top-K 堆的剪枝逻辑

```mermaid
flowchart LR
    S["对每个候选 offset"] --> H{"TopK 已满？"}
    H -- "未满" --> A["直接入堆"]
    H -- "已满" --> B{"score 比堆顶更好？"}
    B -- "是" --> C["替换堆顶<br/>（堆调整）"]
    B -- "否" --> D["丢弃"]
    A --> Z["下一个候选"]
    C --> Z
    D --> Z
```

- **EUCLIDEAN**：score 越小越好，堆顶是**最大** score
- **COSINE / DOT_PRODUCT**：score 越大越好，堆顶是**最小** score
- 实现见 `LocalVectorStore.java:569-577` 的 `offerCandidate` 方法

---

## 图 3：持久化流程（reconcile / reload）

```mermaid
flowchart TD
    subgraph Reconcile["reconcile（集合级对账，内存为权威）"]
        R1["调用方 POST /stores/{name}/reconcile"] --> R2["VectorEngineClientImpl.reconcileStore"]
        R2 --> R3["documentPersistence.reconcileStore(store)"]
        R3 --> R4["按 docId 集合差修复真相源：<br/>补齐缺失行、软删滞留行"]
        R4 --> R5["更新 Store 元数据，<br/>返回 ReconcileResult 对账明细"]
    end

    subgraph Reload["reload（磁盘 → 内存）"]
        L1["调用方 POST /stores/{name}/reload<br/>或应用启动时自动"] --> L2["VectorEngineClientImpl.reload"]
        L2 --> L3["persistence.loadStore(store)"]
        L3 --> L4["按主键分页读取数据库文档与元数据"]
        L4 --> L5["重建 vectorBuffer / PayloadStorage"]
        L5 --> L6["重建 IdOffsetIndex 与 MetadataFilterIndex"]
    end
```

### 持久化的关键不变量

- **写透数据库**：文档 upsert/delete 成功即写入 MongoDB 或 PostgreSQL，避免本地快照刷盘窗口
- **启动可恢复**：按主键分页读取数据库记录，重建向量、Payload 和过滤索引
- **reconcile 用于对账**：显式触发集合级对账（以内存为权威修复漂移），返回 diff 明细；日常收敛由增量同步承担

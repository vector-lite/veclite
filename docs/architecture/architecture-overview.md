# VecLite 架构图（Architecture Overview）

> 基于 `src/main/java/veclite` 源码逆向绘制。  
> 图 1 展示 **分层与模块依赖**；图 2 展示 **运行时对象关系**（一个 Store 实例化后的内部构成）。

## 图 1：分层架构

```mermaid
graph TB
    subgraph External["外部依赖"]
        User["调用方<br/>（前端 / 业务服务）"]
        Embed["Embedding 服务<br/>（HTTP · OpenAI 兼容）"]
    end

    subgraph Web["Web 层（HTTP 入口）"]
        Ctrl["VectorLiteDebugController<br/>REST 11 个端点<br/>@RequestMapping /veclite/api/v1"]
    end

    subgraph API["API 层（接口契约）"]
        EC["VectorEngineClient"]
        SM["VectorStoreManager"]
        EP["EmbeddingProvider"]
    end

    subgraph Embedding["Embedding 适配层"]
        ES["EmbeddingService<br/>· 模型管理<br/>· 版本归一化<br/>· 批量分片"]
        HP["HttpEmbeddingProvider<br/>· HTTP POST<br/>· 三种响应解析"]
    end

    subgraph Engine["Engine 层（核心实现）"]
        LE["LocalVectorEngine<br/>· Store 注册表<br/>· 多 Store 调度"]
        LS["LocalVectorStore<br/>· 写入 / 检索入口<br/>· 软删除 / 量化漂移防护"]
        VB["FloatVectorBuffer<br/>OffHeapSQ8Buffer"]
        IDX["IdOffsetIndex<br/>IntLongIdIndex"]
        DBS["DeletedBitSet"]
        MFI["MetadataFilterIndex<br/>（倒排位图）"]
        PS["CompactPayloadStorage<br/>MMapPayloadStorage"]
    end

    subgraph Quant["量化 & 数学"]
        SQ["SQ8Quantizer"]
        VM["PureJavaVectorMath"]
    end

    subgraph Persistence["持久化层"]
        VPS["VectorPersistenceStorage<br/>（接口）"]
        SFS["SnapshotFileStorage"]
        NPS["NoopVectorPersistenceStorage"]
    end

    subgraph Config["配置 / 装配"]
        VP["VectorLiteProperties<br/>@ConfigurationProperties"]
        AC["VectorLiteAutoConfiguration"]
        OAS["VectorLiteOpenApiConfiguration"]
    end

    subgraph Model["Model 层（DTO）"]
        Doc["VectorDocument"]
        Req["VectorSearchRequest"]
        Res["VectorSearchResult"]
        Def["VectorStoreDefinition"]
        Other["其他枚举 / 过滤器"]
    end

    User -->|"HTTP / JSON"| Ctrl
    Embed -->|"HTTP POST"| HP

    Ctrl --> EC
    Ctrl --> SM
    EC --> LE
    SM --> LE
    LE --> LS

    LS --> VB
    LS --> IDX
    LS --> DBS
    LS --> MFI
    LS --> PS
    LS --> VM
    LS --> SQ

    EC --> EP
    EP -.实现.-> HP
    ES --> EP
    ES --> VP
    ES --> HP

    LE --> VPS
    LE --> SFS
    LE -.可选.-> NPS

    AC --> VP
    AC --> LE
    AC --> ES
    AC --> HP
    OAS --> Ctrl

    EC --> Doc
    EC --> Req
    EC --> Res
    LS --> Doc
    LS --> Req
    LS --> Res
    SM --> Def
    ES --> Other
```

### 分层职责

| 层 | 包 | 职责 | 是否可被上层替换 |
|---|---|---|---|
| **Web** | `veclite.web` | Spring MVC 暴露 HTTP 端点 | 可关（`veclite.web.enabled=false`） |
| **API** | `veclite.api` | 客户端可见的接口契约 | 是接口，可多实现 |
| **Embedding** | `veclite.embedding` | 外部 Embedding 服务的 HTTP 适配 | `EmbeddingProvider` 可替换为本地 ONNX 实现 |
| **Engine** | `veclite.engine` | 内存向量库核心（索引 / 缓冲区 / 过滤） | `LocalVectorEngine` 是默认实现 |
| **Quant / Math** | `veclite.quantization` `veclite.math` | SQ8 量化 + 距离计算 | `VectorMath` 可替换 native 实现 |
| **Persistence** | `veclite.persistence` | 内存 → 磁盘的快照序列化 | `VectorPersistenceStorage` 可替换 |
| **Config** | `veclite.config` | Spring Boot 自动装配 + 配置绑定 | — |
| **Model** | `veclite.model` | 跨层传输的 DTO / 枚举 | — |

---

## 图 2：LocalVectorStore 内部结构（运行时对象图）

```mermaid
graph LR
    subgraph Store["LocalVectorStore（一个向量库实例）"]
        DEF["VectorStoreDefinition<br/>· dimension<br/>· metric<br/>· maxCapacity<br/>· quantization<br/>· embeddingModel<br/>· indexedMetadataFields"]

        VB["FloatVectorBuffer<br/>OffHeapSQ8Buffer<br/>──<br/>平铺连续 float[]/byte[]<br/>（堆内或堆外）"]
        IDX["IdOffsetIndex<br/>──<br/>ID → offset 映射<br/>(long[] 平铺)"]
        DBS["DeletedBitSet<br/>──<br/>软删除位图"]
        MFI["MetadataFilterIndex<br/>──<br/>field=value → BitSet"]
        PS["PayloadStorage<br/>──<br/>text + metadata<br/>（堆内或 mmap）"]
        VM["VectorMath<br/>──<br/>距离计算策略"]
    end

    subgraph External["外部"]
        PROPS["VectorLiteProperties<br/>（searcher/storage 配置）"]
        PERS["VectorPersistenceStorage<br/>（refresh / reload）"]
    end

    DEF -.维度/度量/量化.-> VB
    DEF -.索引字段.-> MFI
    DEF -.维度校验.-> VM
    PROPS -->|"并行阈值 / mmap 开关"| Store
    PERS <-->|"saveStore / loadStore"| VB
    PERS <-->|"saveStore / loadStore"| PS

    Store -->|"upsert / delete / search"| VB
    Store --> IDX
    Store --> DBS
    Store --> MFI
    Store --> PS
    Store --> VM
```

### 6 大组件的协作关系

| 组件 | 文件 | 内存布局 | 关键作用 |
|---|---|---|---|
| `FloatVectorBuffer` / `OffHeapSQ8Buffer` | `engine/FloatVectorBuffer.java`<br>`engine/OffHeapSQ8Buffer.java` | 平铺 `float[]` 或 `byte[]`（堆外 DirectByteBuffer） | 存原始向量本体，连续访问 = 缓存友好 |
| `IdOffsetIndex` | `engine/IdOffsetIndex.java`<br>`engine/IntLongIdIndex.java` | 开放寻址 `long[]` 字典 | ID ↔ 缓冲区下标，**避免 HashMap 装箱** |
| `DeletedBitSet` | `engine/DeletedBitSet.java` | `BitSet` | 软删除标记，**延迟回收**避免写时拷贝 |
| `MetadataFilterIndex` | `engine/MetadataFilterIndex.java` | 倒排位图 | 过滤时给一个候选 BitSet，**纳秒级** |
| `PayloadStorage` | `engine/CompactPayloadStorage.java`<br>`engine/MMapPayloadStorage.java` | 堆内 Map 或 mmap 文件 | text + metadata 不进主检索堆 |
| `VectorMath` | `math/PureJavaVectorMath.java` | 函数指针 | COSINE / EUCLIDEAN / DOT_PRODUCT 距离 |

---

## 关键设计约束

- **强一致**：所有组件通过 `offset`（缓冲区下标）关联，写入时**先追加再回填 ID**，避免中途崩溃出现 ID 指向不存在的 offset
- **软删除**：删除只翻 `DeletedBitSet` 的位，**物理回收延迟**，检索时跳过
- **量化漂移防护**：SQ8 的 per-dimension 校准用前 N 条向量冻结参数，写入新向量时**不重新校准**
- **零 GC 热路径**：Top-K 候选用 `PriorityQueue`，循环内**零临时分配**（v2.4 重点优化）

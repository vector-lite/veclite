# VecLite v2.5 MongoDB 单一真相源持久化设计方案

## 1. 背景与目标

v2.4 设计稿规划了"PostgreSQL 元数据 + OSS 向量快照"的混合持久化架构。经评估，当前实际规模为 **约 10 个 Store、单库数百至 5 万条向量**，远未达到需要对象存储分层的量级。v2.5 方案将架构收敛为：

- **MongoDB = 唯一真相源（Single Source of Truth）**：存储文档 text、metadata 与原始 Float32 向量，写入即持久化（ACID）。
- **内存引擎 = 运行时计算层**：启动时从 MongoDB 全量装载，SQ8 等量化结构为运行时派生物，不落库。
- **本地快照 = 尽力而为的缓存（可选）**：仅为重启提速，校验失败或过期时直接丢弃并从 MongoDB 重建，不参与一致性保证。

### 核心收益

| 维度 | v2.4 混合方案（DB 指针 + OSS 快照） | v2.5 MongoDB 单真相源 |
| --- | --- | --- |
| 一致性 | 协议保证（先传 OSS 后提交指针 + CAS + 对账） | 结构性消失（单一真相源） |
| RPO | 秒级（刷盘间隔） | **0**（写入即持久化） |
| 组件 | DB + OSS + 本地缓存 | 仅 MongoDB（+ 可选本地缓存） |
| 刷盘/指针/对账 | 必需 | 全部不再需要 |
| 适用规模 | 百万级以上 | 十万级以内 |

### 关键洞察

向量是文本的派生物（`text → embedding 模型 → vector`）。只要 text 在真相源中，向量本身永远可以重算；直接存原始 Float32 向量则连重算都省了。因此一致性协议被"真相只有一份"彻底消解。

---

## 2. 数据模型

### 2.1 MongoDB 集合设计（`veclite_document`）

```javascript
// db.createCollection("veclite_document")
// 唯一复合索引
db.veclite_document.createIndex({ store_name: 1, doc_id: 1 }, { unique: true })
```

| 字段 | BSON 类型 | 说明 |
| --- | --- | --- |
| `store_name` | String | 所属 Store 名称 |
| `doc_id` | String | 文档 ID（业务主键） |
| `text` | String | 文档正文（embedding 输入，真相之一） |
| `metadata` | Object | 业务 Key-Value 元数据（真相之一） |
| `vector` | **BinData (subtype 0)** | FLOAT32：原始 Float32 小端序列化（4 字节/维）；SQ8：逐向量量化字节（1 字节/维）；可空 |
| `vector_format` | String | `FLOAT32`（写透路径，原始向量）或 `SQ8`（冻结态全量对账，量化字节），格式与 SQ8 参数配套存储于 meta 集合 |
| `vector_dim` | Int32 | 向量维度（校验用） |
| `embedding_model` | String | 生成该向量所用的模型名（换模型时识别重嵌依据） |
| `updated_at` | Date | 更新时间 |

> **向量必须存 BinData 而非 BSON 数组**：double 数组每元素带类型标记 + 8 字节，1536 维原始 6KB 会膨胀到 15KB+；BinData 无膨胀（4 字节/维），编解码为纯内存操作（`float[] ↔ byte[]` 小端互转，复用 `SnapshotFileStorage` 写 `vectors.bin` 的序列化逻辑）。

> **必须存原始 Float32 而非 SQ8**：① SQ8 逐维 min/scale 是全量数据统计量，数据分布漂移后无法用旧字节重算；② 丢失 Float32 精排能力，召回率永久封顶在 SQ8 水平；③ 量化方案升级无法作用于存量数据。存储代价（单库 5w × 1536 维 = 300MB）完全可控。

### 2.2 Store 级元数据（`veclite_store_meta` 集合）

保留一份轻量 Store 注册表（1 库 1 文档），用于启动发现、配置恢复与 `stats` 查询：

| 字段 | 说明 |
| --- | --- |
| `store_name` | 主键 |
| `dimension` / `metric` / `max_capacity` / `embedding_model` / `quantization` / `indexed_metadata_fields` | 与 `VectorStoreDefinition` 对齐 |
| `active_count` | 有效向量条数（异步/批量后更新，供管理侧展示，不作为正确性依据） |
| `persistence_mode` | **数据位置记录**（见 §3.2）：`MONGODB` / `SNAPSHOT_FILE` / `HYBRID` |
| `sq8_min_per_dim` / `sq8_scale_per_dim` | BinData：SQ8 冻结态的逐维量化参数（Float32 小端），与文档的 `vector_format: SQ8` 配套，装载时经 `restoreFrozenParams` 注入实现位级精确往返 |
| `created_at` / `updated_at` | 时间戳 |

---

## 3. 一键开关与平滑迁移

### 3.1 全局开关（控制面）

沿用现有机制，**不引入新概念**：`StorageType` 枚举新增 `MONGODB`（预留 `HYBRID`），`VectorLiteAutoConfiguration` 按 `veclite.storage.type` 条件装配对应 `VectorPersistenceStorage` 实现（现有 `@ConditionalOnMissingBean` 分支模式不变）：

```java
public enum StorageType {
    NOOP,
    SNAPSHOT_FILE,
    MONGODB,
    HYBRID   // 预留，v2.5 不实现
}
```

```yaml
veclite:
  storage:
    type: MONGODB            # NOOP | SNAPSHOT_FILE | MONGODB | (HYBRID 预留)
    mongodb:
      uri: ${MONGO_URI}
      database: veclite
      document-collection: veclite_document
      meta-collection: veclite_store_meta
    snapshot-file:           # 本地快照缓存（可选，加速重启）
      base-path: ./data/snapshot
    payload:
      mode: MMAP
```

业务代码（`VectorEngineClientImpl`、API 调用方）零改动——`VectorPersistenceStorage` 接口语义（save/load/delete）不变，仅换实现。

### 3.2 关于"每个 Store 元数据里加参数选方案"的修正

直接在 `VectorStoreDefinition` 中增加一个由调用方选择的持久化方案参数，**不合理**，理由：

1. 存储后端是**部署级基础设施决策**，不是 Store 的业务属性；让 `createStore` 调用方感知 OSS/Mongo 违背端口隔离（`docs/architecture.md`：persistence 以接口作为端口，实现隔离在基础设施侧）。
2. 两种方案的**数据位置完全不同**（MongoDB 方案数据在 Mongo 集合；HYBRID 方案数据在 OSS 快照），按 Store 混用意味着同一个系统里存在两套数据目录、两条恢复路径、两套运维与备份策略，故障面翻倍。
3. "哪个 Store 用哪套"本身也需要一个真相源，问题被外推而非消解。

**修正后的语义**：`persistence_mode` 仍然存在于 Store 元数据中，但它是**系统维护的"数据位置记录"，不是调用方的选择参数**：

- 写入路径：Store 数据实际落在哪个后端，系统就把它记录为哪个值（由当前全局 `storage.type` 决定）。
- 读取路径：启动装载时按该记录选择后端——**即使全局开关已切到 MONGODB，仍能识别并加载历史上落在 SNAPSHOT_FILE 的存量库**，不会因为一键切换导致存量数据"失明"。
- 迁移路径：全局开关 + 每库记录组合，支持灰度迁移（见 §3.3）。

### 3.3 迁移路径（SNAPSHOT_FILE → MONGODB）

1. 配置切 `storage.type: MONGODB`，重启。
2. 启动发现阶段：先查 MongoDB `veclite_store_meta`，再扫描本地快照目录；对本地存在但 Mongo 中无记录（或 `persistence_mode != MONGODB`）的 Store，按快照装载进内存，元数据登记进 Mongo 并标记 `persistence_mode: SNAPSHOT_FILE`。
3. 提供 `migrateStore(storeName)` 管理端点：将该 Store 全量文档以 batch 写入 `veclite_document`，成功后将 `persistence_mode` 翻转为 `MONGODB`。
4. `persistence_mode` 为 `MONGODB` 后，写入走 Mongo，本地旧快照目录异步清理。

未迁移的库在 MONGODB 模式下仍以"快照为真相 + 内存写暂停或透写快照"运行，直至逐库迁移完成；建议迁移窗口内冻结写入或接受该库 RPO 退化为快照间隔。

---

### 3.4 PostgreSQL 扩展点（预留）

为实现"将来可切换 PostgreSQL"，文档持久化按**端口-适配器**分两层，MongoDB 仅是当前适配器：

```
VectorEngineClientImpl（引擎层, 零改动）
        │ 依赖
DocumentBackedPersistence（写透编排端口, extends VectorPersistenceStorage）
        │ 委托
VectorDocumentRepository（数据源端口, 存储无关）          ← 扩展点
        ▲                        ▲
MongoVectorDocumentRepository     PostgresVectorDocumentRepository（未来实现）
```

`VectorDocumentRepository` 接口只声明"文档 + 元数据的 CRUD 与扫描"，不暴露任何 MongoDB 类型；未来落地 PostgreSQL 时的对应关系：

| `VectorDocumentRepository` 方法 | PostgreSQL 实现（示意 DDL） |
| --- | --- |
| `upsertBatch` / `scan` / `deleteByIds` / `count` / `listDocumentIds` | `veclite_document` 表（`store_name`+`doc_id` 主键, `vector BYTEA`, `vector_format VARCHAR`, `metadata JSONB`, JDBC batch） |
| `saveStoreMetadata` / `findStoreMetadata` / `listStoreMetadata` | `veclite_store_meta` 表（v2.4 稿 §4.1 DDL 可直接复用, 增加 `persistence_mode` 列） |

新增 PostgreSQL 支持的完整步骤：① 实现 `PostgresVectorDocumentRepository`；② `StorageType` 增加 `POSTGRES`；③ `VectorLiteAutoConfiguration` 装配分支加一个 case。引擎层与编排层零改动，`persistence_mode` 数据位置记录语义（§3.2）对 PostgreSQL 同样适用。

## 4. 写入路径

```
upsert / upsertBatch
    │
    ▼
1. autoEmbed(text → float[])                     [内存, 现有逻辑]
2. MongoDB 事务性提交:
   replaceOne({store_name, doc_id}, {...}, upsert)   [含 vector BinData]
   —— 批量导入使用 ordered=false bulkWrite, 500~1000 条/批
3. DB 提交成功后更新内存 Store（FloatVectorBuffer / 位图索引 / payload）
   —— DB 失败则内存不落, 保证内存永远是 DB 的超集投影
```

- **删除**：先删 DB 行（真相），成功后更新内存位图与索引。
- **active_count**：批量写入后由后台任务按 `countDocuments` 刷新，不阻塞写路径。
- 写路径新增一次 DB 往返（毫秒级），规模下无感；DB 不可用时**写不可用、读不受影响**（检索全走内存）。

## 5. 重启恢复路径

```
启动
 │
 ▼
listAll(): 读 veclite_store_meta + 扫描本地快照目录（双发现, 见 §3.3）
 │
 ▼
逐 Store 并行装载（CompletableFuture 线程池）:
 ├─ persistence_mode == MONGODB
 │    ├─ L1: 本地快照缓存命中且 manifest 校验通过 → mmap 零拷贝装载（毫秒级）
 │    │       缓存版本 stale 时可选: 先服务旧缓存 + 后台刷新缓存（v2.5 可不做, 直接 L3）
 │    └─ L3: Mongo 游标流式读 veclite_document（batchSize 500~1000）
 │           → 向量直接写入 FloatVectorBuffer
 │           → payload 写入 MMapPayloadStorage / 堆
 │           → metadata 建 MetadataFilterIndex 位图
 │           → 全量装载完成后 SQ8 校准 + 冻结（亚秒级, 单遍统计）
 │           → 异步刷新本地快照缓存
 └─ persistence_mode == SNAPSHOT_FILE → 现有 SnapshotFileStorage.loadStore
```

预期恢复耗时：本地缓存命中毫秒级；Mongo 全量重建 5w 条 / 300MB 约 2~5 秒（内网 + BinData 无膨胀解码）。**缓存地位是"尽力而为"**：损坏、版本不符、缺失一律丢弃走 L3，不存在缓存一致性协议。

## 6. 代码落点

| 改动 | 包/类 | 说明 |
| --- | --- | --- |
| `StorageType` 增加 `MONGODB`、预留 `HYBRID` | `veclite.model` | 全局开关枚举 |
| `VectorStoreMetadata`（新） | `veclite.api` | Store 级元数据 DTO，含 `persistenceMode` 与 SQ8 参数，提供 `fromDefinition`/`toDefinition` |
| `VectorStorageFormat`（新） | `veclite.persistence` | 向量落库格式：`FLOAT32`（写透路径）/ `SQ8`（冻结态全量对账） |
| `VectorDocumentEntity`（新） | `veclite.persistence` | 存储无关文档实体，含 `float[] ↔ byte[]` 小端编解码 |
| `VectorDocumentRepository`（新，端口） | `veclite.persistence` | 数据源端口（§3.4 扩展点），未来 PostgreSQL 实现此接口 |
| `DocumentBackedPersistence`（新） | `veclite.persistence` | 写透编排端口，extends `VectorPersistenceStorage`：upsert/delete 文档 + 元数据维护 |
| `MongoVectorDocumentRepository`（新） | `veclite.persistence.mongo` | 端口的 MongoDB 适配器（唯一复合索引、bulkWrite、游标扫描） |
| `MongoVectorPersistenceStorage`（新） | `veclite.persistence.mongo` | 实现 `DocumentBackedPersistence`：写透、全量对账（saveStore）、游标重建（loadStore） |
| `LocalVectorStore.findIdsByFilter`（新） | `veclite.engine` | 返回过滤命中的文档 ID，供 `deleteByFilter` 写透删除；`deleteByFilter` 重构为 `findIdsByFilter + deleteByIds`，行为不变 |
| `VectorLiteProperties.StorageConfig` 增加 `mongodb` 配置节 | `veclite.config` | uri / database / collections / scanBatchSize |
| `VectorEngineClientImpl` 写透与发现 | `veclite.engine` | upsert/delete 先提交 DB 再改内存；启动双发现（properties + meta 集合）；`createStore` 幂等装载已持久化数据 |
| `VectorLiteAutoConfiguration` 装配分支 | `veclite.config` | `MONGODB` → Mongo 编排器（close 自动推断销毁） |

依赖说明：`mongodb-driver-sync` 以 `implementation` 引入（Spring Boot BOM 管理版本），保证 `MONGODB` 模式开箱即用；若后续要求不使用该模式的接入方零依赖，可降级为 `compileOnly` + 文档声明用户自备驱动，引擎与编排代码无需变更。

## 7. 测试规划

- **单元/回归**（无 Tag，秒级）：`float[] ↔ byte[]` 编解码往返一致性；`VectorDocumentEntity` 映射；嵌入式/内存 Mongo（如 `mongo` docker Testcontainers 或 flapdoodle）下的 save→load 往返、删除恢复、损坏快照缓存降级到 Mongo 重建。禁止向仓库提交生成的快照数据，测试一律使用临时目录与固定种子随机向量。
- **基准**（`@Tag("benchmark")`）：Mongo 批量写入吞吐（500/1000 一批）；L3 全量重建耗时随规模曲线（1w/5w）。
- **压测**（`@Tag("stress")`）：10 库并发恢复；DB 抖动时读路径可用性。
- **手动联调**（`@Tag("manual")`）：真实 MongoDB + 迁移流程端到端。

## 8. 明确不做（v2.5 范围外）

- HYBRID（DB 指针 + OSS 快照）实现：仅保留枚举占位与本文档迁移说明；规模到达百万级、快照体积 GB 级时再按 v2.4 设计稿落地，届时 `VectorPersistenceStorage` 接口无需变更。
- WAL/redo log：Mongo 单真相源下 RPO 已为 0，无必要。
- 向量进 DB 检索（pgvector / Atlas Vector Search）：检索始终由内存引擎承担，DB 只做存储。

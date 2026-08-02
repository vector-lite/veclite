# Veclite 向量 SDK 全局架构与演进设计方案 (V2.2)

## 1. 项目定位与设计哲学

**Veclite** 是一款面向 Java / Spring Boot 生态的**嵌入式轻量级向量搜索引擎 SDK**。

### 1.1 核心定位
* **零外部依赖**：无需搭建 Elasticsearch、Milvus 或 RedisVector 集群，轻量 JAR 包嵌入 Spring Boot 即可使用。
* **资源极致亲和**：专为单机受限资源（如 1核1G / 2核4G 宿主机、Edge 边缘节点）设计。
* **高吞吐低延迟**：通过零拷贝连续内存、SQ8 标量量化与位图过滤，提供十万/百万级向量毫秒级响应。

### 1.2 核心设计原则
1. **零多余 Java 对象头**：拒绝在内存中维护数百万个小 `HashMap` 或 `Float[]` 包装对象，全量数据尽量平铺连续存储。
2. **职责清晰**：语义模糊匹配交给**向量相似度计算**；硬性属性约束（租户、分类、状态）交给**高效位图过滤**；不支持低效的元数据全表文本模糊扫描 (`LIKE`)。
3. **透明开关与弹性调优**：所有优化模块（SQ8 量化、多线程并行、堆外内存、倒排索引）均提供 `application.yml` 配置开关。

---

## 2. 已实现的架构与核心功能

```
                           ┌─────────────────────────────────────────┐
                           │       VectorEngineClient (API 层)       │
                           └────────────────────┬────────────────────┘
                                                │
                                    ┌───────────┴───────────┐
                                    ▼                       ▼
                        ┌───────────────────────┐   ┌───────────────┐
                        │   LocalVectorEngine   │   │  Snapshot     │
                        │    (Store 管理中心)   │   │  Persistence  │
                        └───────────┬───────────┘   └───────────────┘
                                    │
                                    ▼
                        ┌───────────────────────┐
                        │   LocalVectorStore    │
                        ├───────────────────────┤
                        │ - IdOffsetIndex       │
                        │ - DeletedBitSet       │
                        │ - Storage Buffer      │
                        └───────────────────────┘
```

### 2.1 连续内存向量缓冲区 (`FloatVectorBuffer` / `sq8Data`)
* **Float32 模式**：一维 `float[]` 连续平铺存储，消除小数组对象头。
* **纯 SQ8 模式**：一维 `byte[]` 连续平铺存储，实现 4 倍内存压缩（512 维向量单条仅占 512 字节），且开启 SQ8 时**不分配 Float32 浮点缓冲区**。

### 2.2 多线程并行搜索 (`ParallelSearchExecutor`)
* **阈值降级**：数据量低于 `minVectorCount`（默认 10,000 条）自动降级为单线程，避免线程分发开销。
* **Chunk 分段并发**：超过阈值时按线程数划分区间，多线程并发计算局部 Top-K 后在主线程做 Top-K 合并。

### 2.3 文件快照持久化 (`SnapshotFileStorage`)
* 采用 `.tmp` 目录 + 物理文件覆盖 (`REPLACE_EXISTING`) 保证原子落盘。
* SQ8 模式下 `vectors.bin` 直接刷盘/恢复二进制字节数据，刷盘效率提升 18%，快照体积缩减 4 倍。

---

## 3. V2.2 最新演进架构：轻量化元数据与前置过滤设计

为了彻底解决目前 100 万向量下元数据 `HashMap` 产生的 **~2.0 GB 对象头与引用指针膨胀** 问题，V2.2 将引入 **倒排位图** 与 **Payload 离线化** 架构。

```
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │                       JVM Heap / Off-Heap 极简常驻内存                      │
 ├─────────────────────────────────────────────────────────────────────────────┤
 │ 1. 向量平铺 Buffer (SQ8 字节数组，100万条仅占 476 MB)                        │
 │ 2. 倒排位图索引 InvertedBitSetIndex (用于 EQ / IN 过滤，100万条仅 128 KB)    │
 │ 3. 列式数值数组 ColumnarValueArray (用于 RANGE 过滤, long[]/float[])        │
 └──────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                         【前置过滤 & 向量相似度计算 Top-K】
                                        │
                                        ▼
                                给出 Top-10 ID 列表
                                        │
                               【反查提取完整 Payload】
                                        │
 ┌──────────────────────────────────────┴──────────────────────────────────────┐
 │             磁盘文件 / 堆外二进制 Payload 存储 (Text & Metadata JSON)       │
 └─────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 倒排位图索引 (`InvertedBitSetIndex`)

针对 `EQ` (精确匹配) 和 `IN` (列表匹配) 查询，在前置过滤时**无需读取或解析任何 JSON/HashMap**。

#### 1. 精确匹配 (`EQ`)
* 为配置了索引的元数据字段（`indexedMetadataFields`）维护倒排表：`Map<String, Map<Object, BitSet>>`。
* 过滤 `category = 'tech'` 时，直接获取 `tech_bitset`，在遍历向量时做 `bitset.get(offset)` 判断。

#### 2. 列表匹配 (`IN`)
* 针对 `category IN ['tech', 'ai']`，直接对多个 BitSet 执行 CPU 原生按位或操作（`Bitwise OR`）：
  $$\text{TargetBitSet} = \text{BitSet}_{\text{tech}} \mid \text{BitSet}_{\text{ai}}$$
* 1 纳秒即可完成合并，极速支持多标签、多租户（`tenant_id IN [...]`）等高频业务场景。

---

### 3.2 列式数值数组 (`ColumnarValueArray`)

针对 `RANGE` (数值范围：`>`, `<`, `>=`, `<=`) 查询：
* 采用扁平的基本类型数组平铺存储（如 `long[] timestamps` / `float[] prices`）。
* 遍历时直接按数组下标比对，零 JVM 对象头，且贴合 CPU Cache 预取。

---

### 3.3 Payload 堆外与二进制离线化 (`OffHeapPayloadStorage`)

* **内存仅留 ID 映射**：JVM 堆内只保留轻量级 `IdOffsetIndex` 和索引位图。
* **Payload 延迟加载**：完整的 JSON 元数据与 Text 文本平时序列化保存在二进制文件或堆外 Buffer 中。
* **Top-K 提取**：仅在向量搜索算出最终 Top-10 / Top-20 的 `offset` 结果后，才按需去磁盘/堆外读取这 10 条文档的文本与 JSON 数据。

#### 预期效果：
* 100 万条 512 维向量的 **JVM 堆内存占用将从当前的 3.0 GB 暴降至 < 800 MB**！

---

### 3.4 过滤能力边界声明

| 操作符 | 示例 | 状态 | 技术实现与理由 |
| :--- | :--- | :---: | :--- |
| **`EQ`** | `category = 'tech'` | **支持** | 倒排 BitSet 位图，零对象匹配 |
| **`IN`** | `category IN ['tech', 'ai']` | **支持** | BitSet 按位或 (`\|`)，1 纳秒合并 |
| **`RANGE`** | `price >= 100` | **支持** | 列式平铺数组，CPU Cache 友好 |
| **`LIKE`** | `title LIKE '%gpt%'` | **不支持** | 语义模糊交给向量检索，避免全表扫文本 |

---

## 4. 全量配置规范 (`application.yml`)

```yaml
veclite:
  enabled: true
  
  # 1. 搜索计算配置
  searcher:
    parallel:
      enabled: true               # 是否开启多线程并行搜索
      threads: 4                  # 并行线程数
      min-vector-count: 10000     # 触发并行搜索的最小条数

  # 2. 存储与持久化配置
  storage:
    type: SNAPSHOT_FILE
    off-heap:
      enabled: false              # 是否开启堆外内存存储向量与 Payload
    snapshot-file:
      base-path: ./data/vec
      flush-interval-seconds: 30

  # 3. Vector Store 配置
  stores:
    knowledge-base:
      dimension: 512              # 向量维度
      max-capacity: 1000000       # 最大容量
      metric: COSINE              # 距离度量: COSINE / EUCLIDEAN / DOT_PRODUCT
      quantization: SQ8           # 量化模式: NONE / SQ8
      indexed-metadata-fields:    # 构建倒排位图索引的字段列表
        - category
        - tenant_id
        - status
```

---

## 5. 演进路线图 (Roadmap)

```
┌───────────────────────────┐      ┌───────────────────────────┐      ┌───────────────────────────┐
│     V1.0 (已完成)         │      │     V2.1 (已完成)         │      │     V2.2 (当前版本及目标) │
├───────────────────────────┤      ├───────────────────────────┤      ├───────────────────────────┤
│ - FloatVectorBuffer 连续内存│      │ - 纯 SQ8 8-bit 量化存储   │      │ - 倒排 BitSet 索引 (EQ/IN)│
│ - Snapshot 快照落盘       │      │ - 多线程 Chunk 并行搜索   │      │ - 列式数组 (RANGE)        │
│ - 基础单线程 Flat 搜索    │      │ - 自动化 210万向量基准压测 │      │ - Payload 堆外/离线化     │
└───────────────────────────┘      └───────────────────────────┘      └───────────────────────────┘
```

1. **Phase 1 (V2.2-Alpha)**：实现 `InvertedBitSetIndex` 倒排位图模块，支持 `EQ` 和 `IN` 零对象前置过滤。
2. **Phase 2 (V2.2-Beta)**：实现 Payload 磁盘/堆外二进制存储，将 100 万向量总堆内存压至 800MB 以内。
3. **Phase 3 (V2.2-GA)**：全量基准测试与 1核1G 受限资源下的高并发 QPS 校验。

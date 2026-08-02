# Veclite 向量 SDK 全局架构与演进设计方案 (V2.3)

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

## 2. 已实现的架构与核心功能汇总 (V1.0 ~ V2.2)

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
                        │ - MetadataFilterIndex │
                        │ - CompactPayload      │
                        │ - SQ8 Data Buffer     │
                        └───────────────────────┘
```

### 2.1 连续内存向量缓冲区 (`FloatVectorBuffer` / `sq8Data`)
* **Float32 模式**：一维 `float[]` 连续平铺存储，消除小数组对象头。
* **纯 SQ8 模式**：一维 `byte[]` 连续平铺存储，实现 4 倍内存压缩（512 维向量单条仅占 512 字节），且开启 SQ8 时**不分配 Float32 浮点缓冲区**。

### 2.2 多线程并行搜索 (`ParallelSearchExecutor`)
* **阈值降级**：数据量低于 `minVectorCount`（默认 10,000 条）自动降级为单线程，避免线程分发开销。
* **Chunk 分段并发**：超过阈值时按线程数划分区间，多线程并发计算局部 Top-K 后在主线程做 Top-K 合并。

### 2.3 倒排位图前置过滤 (`MetadataFilterIndex`)
* 实现了针对 `EQ` (精准匹配) 和 `IN` (列表匹配) 的 BitSet 表达式求值器。
* `IN` 查询通过 CPU 纳秒级按位或 (`Bitwise OR`) 运算实现多位图实时合并，搜索循环内零对象访问。

### 2.4 紧凑 Payload 存储 (`CompactPayloadStorage`)
* 采用一维引用数组 `String[] ids`、`String[] texts`、`Map[] metadatas` 替代重型的 ConcurrentHashMap。
* 消灭 500+ 万个小 Java 节点对象，210 万向量 5 个 Store 常驻 JVM 的堆内存从 **5.9 GB 暴降至 3.08 GB (降低 47%)**。

---

## 3. V2.3 最新演进架构设计：极致空间压缩与零 GC 堆外化

在 V2.2 架构下，210 万向量占用 3.08 GB 堆内存中，**真正的 SQ8 向量仅占 1.0 GB (32.5%)**，其余 2.08 GB 依然被字符串 ID、元数据 Map 和对象头占据。V2.3 将推出 3 大终极重构模块：

```
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │                         OS 堆外内存 (Direct Memory)                        │
 ├─────────────────────────────────────────────────────────────────────────────┤
 │ 1. OffHeapSQ8Buffer (SQ8 字节数组，210万条仅占 1.0 GB 堆外空间，零 GC)       │
 └──────────────────────────────────────┬──────────────────────────────────────┘
                                        │
 ┌──────────────────────────────────────┴──────────────────────────────────────┐
 │                       JVM Heap 堆内常驻超轻量索引 (< 300 MB)                │
 ├─────────────────────────────────────────────────────────────────────────────┤
 │ 2. InvertedBitSetIndex (用于 EQ / IN 过滤，210万条仅 250 KB)                 │
 │ 3. IntLongIdIndex (扁平 long[] 数值 ID 映射，省去 500 MB 字符串开销)         │
 └──────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                         【前置过滤 & 向量相似度计算 Top-K】
                                        │
                                        ▼
                                给出 Top-10 ID 列表
                                        │
                               【按 Offset 反查提取】
                                        │
 ┌──────────────────────────────────────┴──────────────────────────────────────┐
 │             磁盘 MMap 二进制 Payload 存储 (Text & Metadata JSON)            │
 └─────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 模块一：数值化 ID 字典与轻量映射 (`IntLongIdIndex`)

* **痛点**：目前 `ids[]` 存储的是 210 万个 `String` 对象（如 `"doc_store_100w_999999"`），加上 `IdOffsetIndex` 占据了 **~860 MB** 堆内存。
* **设计方案**：
  - 内部统一使用扁平的 `long[] idArray`（支持自增数字或 64 位 Hash 值）替代 `String[]`。
  - 彻底消灭 ConcurrentHashMap 与装箱 `Integer` 节点。
* **预期效果**：节省 **~500 MB** 堆内存。

---

### 3.2 模块二：Payload MMap 磁盘离线与延迟加载 (`MMapPayloadStorage`)

* **痛点**：当前 `texts[]` 和 `metadatas[]` 常驻堆内存，消耗了 **~930 MB** 空间。
* **设计方案**：
  - 由于前置过滤已由 `MetadataFilterIndex` (BitSet) 独立完成，**向量搜索主循环内完全不需要访问任何文本和 Map 对象**。
  - 在 Upsert 写入时，直接将完整 JSON Payload 与 Text 追加写入磁盘 **MMap 文件 (`payload.mmap`)**。
  - **延迟提取**：仅在向量搜索计算出最终 Top-10 / Top-20 的 `offset` 后，才通过 MMap 快速反查读取这 10 条的详情。
* **预期效果**：从堆内存中再节省 **~900 MB** 空间。

---

### 3.3 模块三：堆外内存向量存储 (`OffHeapSQ8Buffer`)

* **痛点**：1.0 GB 的 `sq8Data` 字节数组分配在 JVM 堆内，触发 Garbage Collector 进行主堆扫描。
* **设计方案**：
  - 使用 `ByteBuffer.allocateDirect(...)` 或 Java 17 `Unsafe` API 将 SQ8 字节缓冲区分配在操作系统堆外内存中。
  - JVM 堆内只保留 KB 级别的指针引用，实现真正的**零 GC 干扰**！

---

## 4. 三阶段演进内存与性能对比

| 架构/演进阶段 | 210万向量总堆内存 | 100万向量单 Store 堆内存 | 单线程写入吞吐 | GC 停顿影响 |
| :--- | ---: | ---: | ---: | :--- |
| **V2.1 (Float32 + 重型 Map)** | 5,902 MB (5.76 GB) | ~3.38 GB | 36.5万 ops/s | 依赖 Minor/Major GC |
| **V2.2 (纯 SQ8 + 倒排 BitSet + CompactPayload)** | **3,156 MB (3.08 GB)** | **~1.20 GB** | **46.5万 ops/s** | 低 GC 影响 |
| **V2.3 (SQ8堆外 + 位图 + Payload MMap延迟加载)**| **< 300 MB (堆外 1.0GB)**| **< 150 MB (堆外 0.47GB)**| **> 50.0万 ops/s**| **零 GC 干扰** |

---

## 5. 全量配置规范 (`application.yml`)

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
      enabled: true               # 是否开启堆外内存存储向量 Buffer (零 GC)
    payload:
      mode: MMAP                  # Payload 存储模式: MEMORY / MMAP (磁盘延迟加载)
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

## 6. 演进路线图 (Roadmap)

```
┌───────────────────────────┐      ┌───────────────────────────┐      ┌───────────────────────────┐
│     V2.1 (已完成)         │      │     V2.2 (已完成)         │      │     V2.3 (下阶段演进目标) │
├───────────────────────────┤      ├───────────────────────────┤      ├───────────────────────────┤
│ - FloatVectorBuffer 连续内存│      │ - 纯 SQ8 8-bit 量化存储   │      │ - OffHeapSQ8Buffer 堆外 Buffer│
│ - Snapshot 快照落盘       │      │ - 倒排 BitSet 索引 (EQ/IN)│      │ - MMap Payload 磁盘延迟加载│
│ - 基础单线程 Flat 搜索    │      │ - CompactPayload 平铺数组 │      │ - IntLongIdIndex 字典编码 │
└───────────────────────────┘      └───────────────────────────┘      └───────────────────────────┘
```

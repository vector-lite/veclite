# Veclite 向量 SDK 全局架构设计方案与演进方向 (V2.4)

## 1. 项目定位与设计哲学

**Veclite** 是一款面向 Java / Spring Boot 生态的**嵌入式轻量级向量搜索引擎 SDK**。

### 1.1 核心定位
* **零外部依赖**：无需搭建 Elasticsearch、Milvus 或 RedisVector 集群，纯 Java 编写，轻量 JAR 包嵌入 Spring Boot 即可使用。
* **零 Native/C++ 依赖**：不依赖任何 C++ 原生动态库（如 FAISS `.so` / `.dylib`），保障 100% 跨平台（Linux, macOS, Windows, ARM64）与零崩溃安全性。
* **资源极致亲和**：专为单机受限资源（如 1核1G / 2核4G 宿主机、Edge 边缘节点）设计。
* **高吞吐低延迟**：通过零拷贝连续内存、SQ8 标量量化与位图过滤，提供十万/百万级向量毫秒级响应。

### 1.2 核心设计原则
1. **零多余 Java 对象头**：拒绝在内存中维护数百万个小 `HashMap` 或 `Float[]` 包装对象，全量数据尽量平铺连续存储。
2. **强一致性保障**：建立完备的 Offset 关联防护机制，杜绝平行数据结构在中途崩溃或并发写时产生偏移错位。
3. **职责清晰**：语义模糊匹配交给**向量相似度计算**；硬性属性约束（租户、分类、状态）交给**高效位图过滤**；不支持低效的元数据全表文本模糊扫描 (`LIKE`)。

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

## 3. V2.4 核心突破：数据强一致性与 Offset 防错位体系

在以 `Offset` 为纽带连接多个平行数据结构（向量 Buffer、ID 索引、倒排 BitSet、Payload 数组）的架构中，数据一致性是系统的生死命脉。V2.4 建立了 **4 重铁律保障机制**：

```
                              用户 Upsert(Doc)
                                     │
                                     ▼
                     ┌──────────────────────────────┐
                     │ 唯一单点 Offset 分配器        │
                     └───────────────┬──────────────┘
                                     │
         ┌───────────────────────────┼───────────────────────────┐
         ▼                           ▼                           ▼
 写入向量 sq8Data[100]    写入 Payload Storage[100]   写入 BitSet.set(100)
```

### 3.1 铁律一：唯一单点 Offset 分配器 (Single Source of Truth)
* 禁止各组件各自计算容量或自增 size。
* 所有的 Offset 分配统一由 `IdOffsetIndex` 或单点分配器全局分发。更新操作严格基于已有 Offset 原地覆盖，绑定关系在整个 Store 生命周期内不可变。

### 3.2 铁律二：事务级 Upsert 与异常回滚机制 (Transactional Upsert)
* `upsert()` 内部引入独占写锁 (`WriteLock` / `synchronized`)，确保高并发写入时递增顺序严格可控。
* 引入两阶段提交与 Exception 捕获：若在写入 Payload 或倒排 BitSet 时发生任何异常，自动触发 `rollbackOffset` 撤回并标记废弃，防止“半成品脏数据”残留导致错位。

### 3.3 铁律三：只追加 (Append-Only) + 软删除，禁止物理移动 Offset
* 日常删除仅操作 `DeletedBitSet`（标记该 Offset 逻辑删除），**严禁在运行期物理移动向量数组**（避免后续向量 Offset 整体偏移）。
* 碎片整理 (Compaction) 仅在手动压缩或磁盘重构时，采用 Stop-The-World 全量重新构建新 Store，从源头杜绝错位风险。

### 3.4 铁律四：Fail-Fast 不变量断言检查 (`assertConsistency`)
* 在刷盘持久化和关键写入节点自动触发不变量强校验：
  ```java
  assert getVectorBufferSize() == payloadStorage.size() && getVectorBufferSize() == idOffsetIndex.size();
  ```
* 一旦校验失败立即触发 Fail-Fast 保护，拒绝刷盘破坏物理文件。

---

## 4. V2.4 选型与演进：纯 Java 自研轻量 IVF 聚类索引

### 4.1 技术选型抉择：自研纯 Java IVF vs C++ FAISS JNI

| 评估维度 | 方案 A：集成 Meta FAISS (C++ JNI) | 方案 B：纯 Java 自研轻量 IVF (推荐) |
| :--- | :--- | :--- |
| **部署体验** | 复杂（必须携带/编译 C++ 动态链接库） | **极其优雅（纯 JAR 包，零 Native 依赖）** |
| **跨平台能力** | 差（Linux/macOS/ARM64 编译和兼容极其头疼） | **完美（彻底跨平台，支持任何 JVM）** |
| **进程稳定性** | 有风险（C++ 段错误 SegFault 会挂掉整个 JVM） | **极高（完全在 JVM 垃圾回收与异常控制内）** |
| **代码契合度** | 割裂（无法复用现有的 BitSet 和 SQ8） | **无缝融合（直接复用 SQ8 / BitSet 模块）** |
| **自研代码量** | 0 行算法（但需写大量复杂的 JNI 适配） | **仅需 300 ~ 500 行纯 Java 代码** |

**结论**：Veclite 的灵魂在于嵌入式与跨平台纯粹性，V2.4 明确采用 **纯 Java 自研 IVF** 路线。

---

### 4.2 自研 IVF 架构设计 (Inverted File Cluster Index)

针对 200万~500万 规模下全量 Flat 计算耗费 CPU 的瓶颈，引入 **IVF 倒排聚类** 索引：

```
聚类中心 (Centroids)              倒排桶 (Inverted Lists)
┌──────────────────┐             ┌──────────────────────────────┐
│ Centroid 0       ├────────────►│ [Vec #1, Vec #42, Vec #99...]│
├──────────────────┤             ├──────────────────────────────┤
│ Centroid 1       ├────────────►│ [Vec #5, Vec #12, Vec #87...]│
├──────────────────┤             ├──────────────────────────────┤
│ Centroid 2 (最近) ├────────────►│ [Vec #3, Vec #18, Vec #77...]│ ──► 仅扫描这 1.6% 的桶
└──────────────────┘             └──────────────────────────────┘
```

1. **聚类训练 (Train)**：利用极简 Mini-Batch K-Means 在纯 Java 中训练出 $K$ 个聚类中心（Centroids，例如 $K=1024$）。
2. **归桶追加 (Insert)**：计算向量与 Centroids 的距离，确定归属后追加至对应桶内。
3. **选桶扫描 (Query)**：检索时计算 Query 与 Centroids 距离，选出最近的 $nprobe$ 个桶（如 $nprobe=16$），在桶内复用 `SQ8Quantizer` 做矢量加速打分。
4. **效果**：每次查询仅需扫描 **1.6% 的向量数据**，200 万向量下的 QPS 提升 50 倍，CPU 占用直接降低 95%！

---

## 5. 四阶段演进内存与性能对比表

| 架构/演进阶段 | 210万向量总堆内存 | 100万向量单 Store 堆内存 | 单线程写入吞吐 | 200万向量单次检索延迟 |
| :--- | ---: | ---: | ---: | ---: |
| **V2.1 (Float32 + 重型 Map)** | 5,902 MB (5.76 GB) | ~3.38 GB | 36.5万 ops/s | ~40 ms (CPU 占用高) |
| **V2.2 (纯 SQ8 + 倒排 BitSet + CompactPayload)** | **3,156 MB (3.08 GB)** | **~1.20 GB** | **46.5万 ops/s** | ~20 ms (全量扫描) |
| **V2.4 (自研纯 Java IVF + SQ8)** | **~3,200 MB (3.12 GB)** | **~1.25 GB** | **40.0万 ops/s** | **< 2 ms (CPU 降低 95%)** |
| **V2.4+ (IVF + 堆外向量 + Payload MMap)** | **< 300 MB (堆外 1.0GB)**| **< 150 MB (堆外 0.47GB)**| **> 50.0万 ops/s**| **< 2 ms (零 GC 干扰)** |

---

## 6. 全量配置规范 (`application.yml`)

```yaml
veclite:
  enabled: true
  
  # 1. 搜索计算配置
  searcher:
    index-type: IVF_SQ8           # 索引类型: FLAT / IVF_SQ8
    ivf:
      nlist: 1024                 # 聚类桶数量
      nprobe: 16                  # 检索时扫描的最近桶数量
    parallel:
      enabled: true               # 是否开启多线程并行搜索
      threads: 4                  # 并行线程数
      min-vector-count: 10000     # 触发并行搜索的最小条数

  # 2. 存储与持久化配置
  storage:
    type: SNAPSHOT_FILE
    off-heap:
      enabled: false              # 是否开启堆外内存存储向量 Buffer (零 GC)
    payload:
      mode: MEMORY                # Payload 存储模式: MEMORY / MMAP (磁盘延迟加载)
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

## 7. 演进路线图 (Roadmap)

```
┌───────────────────────────┐      ┌───────────────────────────┐      ┌───────────────────────────┐
│     V2.2 (已完成)         │      │     V2.4 (当前版本设计)   │      │     V2.4+ (下一阶段目标)  │
├───────────────────────────┤      ├───────────────────────────┤      ├───────────────────────────┤
│ - 纯 SQ8 8-bit 量化存储   │      │ - 4重强一致性与防错位体系  │      │ - MMap Payload 磁盘延迟加载│
│ - 倒排 BitSet 索引 (EQ/IN)│      │ - 纯 Java 300行自研轻量IVF│      │ - OffHeapSQ8Buffer 堆外Buffer│
│ - CompactPayload 平铺数组 │      │ - 事务级 Upsert 与 Rollback│     │ - IntLongIdIndex 字典编码 │
└───────────────────────────┘      └───────────────────────────┘      └───────────────────────────┘
```

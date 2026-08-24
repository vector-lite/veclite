# Veclite 向量 SDK 全局架构设计方案与演进方向 (V2.4)

## 1. 项目定位与设计哲学

**Veclite** 是一款面向 Java / Spring Boot 生态的**嵌入式轻量级向量搜索引擎 SDK**。

### 1.1 核心定位
* **零外部依赖**：无需搭建 Elasticsearch、Milvus 或 RedisVector 集群，纯 Java 编写，轻量 JAR 包嵌入 Spring Boot 即可使用。
* **零 Native/C++ 依赖**：不依赖任何 C++ 原生动态库（如 FAISS `.so` / `.dylib`），保障 100% 跨平台（Linux, macOS, Windows, ARM64）与零崩溃安全性。
* **资源可预测**：以堆外 SQ8、磁盘 Payload 和显式容量预算避免内存骤降时的延迟悬崖。
* **小库稳定检索**：优先服务 20 万级及以下的小向量库；超过该范围由 IVF 降低扫描量，而不是无界扩大 Flat 搜索。

### 1.2 核心设计原则
1. **零多余 Java 对象头**：拒绝在内存中维护数百万个小 `HashMap` 或 `Float[]` 包装对象，全量数据尽量平铺连续存储。
2. **高频计算零重复浪费**：循环内不变的数学项一律提前在查询入口处预计算；消除检索循环内任何临时对象分配。
3. **强一致性保障**：建立完备的 Offset 关联防护机制，杜绝平行数据结构在中途崩溃或并发写时产生偏移错位。
4. **职责清晰**：语义模糊匹配交给**向量相似度计算**；硬性属性约束（租户、分类、状态）交给**高效位图过滤**；不支持低效的元数据全表文本模糊扫描 (`LIKE`)。

### 1.3 V2.4 线上验收目标

* **主场景**：20 万条、512 维、COSINE、SQ8、Top-10、无过滤或过滤后有效候选数不超过 20 万。
* **单实例 SLO**：精确 Flat 查询在目标硬件上达到 **30 QPS**，**P99 不高于 150 ms**；引擎自身应预留队列与网络开销，查询计算 P99 目标不高于 80 ms。
* **服务 SLO**：总计 200 QPS 通过多副本横向扩展承载；单实例 CPU 利用率应保留至少 30% 余量，禁止用无界排队换取表面吞吐。
* **路由边界**：有效候选数不超过 20 万时默认 Flat，超过 20 万时默认 IVF；位图过滤后的候选数而非 Store 总数是最终决策依据。

---

## 2. 已实现的架构与核心功能汇总 (V1.0 ~ V2.3)

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
                        │ - IntLongIdIndex      │
                        │ - DeletedBitSet       │
                        │ - MetadataFilterIndex │
                        │ - MMapPayloadStorage  │
                        │ - OffHeapSQ8Buffer    │
                        └───────────────────────┘
```

* **纯 SQ8 量化与堆外内存 (`OffHeapSQ8Buffer`)**：使用 Direct Memory 连续平铺存储 SQ8 字节数组，210 万向量仅占 1.0 GB 堆外空间，JVM 堆内零 GC 扫描。
* **Payload 磁盘延迟加载 (`MMapPayloadStorage`)**：文档 Text 与 Metadata 脱离主检索堆；当前实现通过 `FileChannel` 按 Top-K 延迟反查，真正的操作系统内存映射属于后续优化项。
* **数值化 ID 字典 (`IntLongIdIndex`)**：采用开放寻址平铺 `long[]` 数组映射，消灭 ConcurrentHashMap 节点与装箱对象。
* **多线程并行与位图过滤 (`MetadataFilterIndex`)**：支持纳秒级按位运算前置过滤与多线程 Chunk 分段搜索。

---

## 3. V2.4 P0：稳定的 SQ8 Flat 热路径

### 3.1 基线与瓶颈
最新单核、512 维、SQ8 堆外基准中，20 万条 Flat 查询中位数为 **120.98 ms**、P95 为 **131.54 ms**、QPS 为 **8.27**。该结果仅是单请求采样，不是 200 QPS 的并发服务结论。

20 万条向量每次查询需要扫描 **1.024 亿个维度**。当前热路径的主要额外成本是：
1. 堆外 `ByteBuffer` 每条向量先复制到 `byte[]`，再进行打分，增加约 102 MB/查询的内存复制。
2. 每条向量重复执行字符串度量判断和 `sqrt(targetNormSq)`。
3. `sq8Min/sq8Max` 随写入变化，历史数据未重编码，既有精度风险，也阻碍写入时预存归一化常量。

---

### 3.2 核心数学推导与代数展开原理

设反量化浮点数为：
$$t_i = \text{min} + (b_i + 128) \cdot \frac{\text{range}}{255}$$

定义常数项：
$$c_2 = \frac{\text{range}}{255}, \quad c_1 = \text{min} + 128 \cdot c_2$$

则 $t_i = c_1 + c_2 b_i$。对于查询向量 $q$，点积为：
$$\sum_{i=1}^{d} q_i \cdot t_i = \sum_{i=1}^{d} q_i (c_1 + c_2 b_i) = c_1 \underbrace{\sum_{i=1}^{d} q_i}_{\text{Query 和 (预计算)}} + c_2 \sum_{i=1}^{d} q_i \cdot b_i$$

```
                         【客户端发起 Search(queryVector)】
                                         │
                                         ▼
                     ┌────────────────────────────────────────┐
                     │ 1. 查询入口预计算                         │
                     │    - querySum = ∑ q[i]                 │
                     │    - queryNormSq = ∑ (q[i] * q[i])     │
                     └───────────────────┬────────────────────┘
                                         │
                                         ▼
         ┌────────────────────────────────────────────────────────────────┐
         │ 2. 底库候选集遍历主循环（直接读取堆外字节）                     │
         │    dot = c1 * querySum + c2 * ∑ (q[i] * b[i])                  │
         │    查询级常量不在循环内重复计算                                │
         └────────────────────────────────────────────────────────────────┘
```

---

### 3.3 写入侧不变量

* 每个 Store 在建库阶段固定 SQ8 量化参数；参数需要变化时，在新版本 Store 中完整重建并原子切换，不能在原 Store 中混用不同量化范围。
* 写入时计算并保存字节和、字节平方和及余弦归一化常量，查询循环不再重复计算目标向量模长。
* Store 的最大容量必须参与堆外分段容量规划；运行期不得通过整块扩容造成旧、新 Direct Buffer 同时驻留。

---

### 3.4 堆外检索循环零分配寻址 (Zero-Allocation Direct Scan)

* `OffHeapSQ8Buffer` 提供分段、绝对偏移读取接口；查询直接在 Direct Buffer 上打分，不复制到中间 `byte[]`。
* 将度量类型在查询入口解析为内部枚举，热循环只保留必要的乘加和 Top-K 比较。
* 标量实现作为正确性基线；JDK 21 Vector API 实现作为可选加速路径，按 CPU 架构和基准结果启用。

---

## 4. V2.4 核心突破二：数据强一致性与 Offset 防错位体系

在以 `Offset` 为纽带连接多个平行数据结构（向量 Buffer、ID 索引、倒排 BitSet、Payload 存储）的架构中，数据一致性是系统的生死命脉。以下四项是 V2.4 必须落地的稳定性门槛：

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

1. **唯一单点 Offset 分配器 (Single Source of Truth)**：禁止各组件各自计算容量或自增 size，所有 Offset 统一由单点全局分发。
2. **事务级 Upsert 与异常回滚机制 (Transactional Upsert)**：引入两阶段写入与异常捕获，若 Payload 或倒排 BitSet 写入失败，自动触发回滚标记作废。
3. **只追加 (Append-Only) + 软删除**：运行期严禁物理移动向量数组，碎片整理（Compaction）仅在显式重构时全量重建。
4. **Fail-Fast 不变量断言检查 (`assertConsistency`)**：快照刷盘前强校验 `VectorBufferSize == PayloadSize == IdIndexSize`，校验不通过拒绝破坏物理文件。

---

## 5. V2.4 核心突破三：纯 Java 自研轻量 IVF 聚类索引设计

### 5.1 架构设计 (Inverted File Cluster Index)

当有效候选数超过 20 万时，全量 Flat 计算将侵蚀 P99 预算；此时引入 **IVF 倒排聚类** 索引。有效候选数由元数据位图过滤后计算，不能只依据 Store 总量：

```
聚类中心 (Centroids)              倒排桶 (Inverted Lists)
┌──────────────────┐             ┌──────────────────────────────┐
│ Centroid 0       ├────────────►│ [Vec #1, Vec #42, Vec #99...]│
├──────────────────┤             ├──────────────────────────────┤
│ Centroid 1       ├────────────►│ [Vec #5, Vec #12, Vec #87...]│
├──────────────────┤             ├──────────────────────────────┤
│ Centroid 2 (最近) ├────────────►│ [Vec #3, Vec #18, Vec #77...]│ ──► 仅扫描这 3% 的桶
└──────────────────┘             └──────────────────────────────┘
```

1. **聚类训练 (Train)**：利用极简 Mini-Batch K-Means 在纯 Java 中训练出 $K$ 个聚类中心（Centroids，例如 $K=256\sim 1024$）。
2. **归桶追加 (Insert)**：计算向量与 Centroids 的距离，确定归属后追加至对应桶内。
3. **选桶扫描 (Query)**：检索时计算 Query 与 Centroids 距离，选出最近的 $nprobe$ 个桶（如 $nprobe=8\sim 16$），在桶内复用预计算加速打分。
4. **验收**：以 Recall@10、P99、CPU 利用率和内存上限共同验收；默认目标是 Recall@10 不低于 98.5%，不承诺脱离硬件和数据分布的固定 QPS 或延迟。

---

## 6. 目标矩阵与验收口径

| 场景 | 默认检索器 | 核心目标 | 验收方法 |
| :--- | :--- | :--- | :--- |
| 20 万条、512 维、有效候选数 <= 20 万 | 精确 Flat SQ8 | 单实例 30 QPS，P99 <= 150 ms | 固定到达率压测，记录 P50/P95/P99、队列时间、拒绝数、CPU、堆和 Direct Memory |
| 20 万条、存在位图过滤 | 精确 Flat SQ8 | 按过滤后候选数保持或优于主场景 | 报告过滤选择率与实际扫描量 |
| 有效候选数 > 20 万 | IVF_SQ8 | 以 P99 和 CPU 换取 98.5%+ Recall@10 | 以 Float32 精确结果为 Ground Truth，测 Recall@K 和延迟 |
| 服务总流量 200 QPS | 多副本本地 Store | P99 <= 150 ms，保留 >= 30% CPU 余量 | 以每副本限流和负载均衡验证，不以单实例无界排队换吞吐 |

---

## 7. 全量配置规范 (`application.yml`)

以下为目标态配置；`AUTO` 路由、`flat-max-candidates` 和 IVF 参数需要在 P3 落地后才可用。

```yaml
veclite:
  enabled: true
  
  # 1. 搜索计算配置
  searcher:
    routing: AUTO                 # AUTO / FLAT / IVF_SQ8
    flat-max-candidates: 200000   # 超过该有效候选数时默认 IVF
    precomputation:
      enabled: true               # 是否开启查询级数学代数展开与预计算加速 (P0)
    ivf:
      nlist: 256                  # 聚类桶数量，按数据规模调优
      nprobe: 8                   # 检索时扫描的最近桶数量，按 Recall@10 调优
    parallel:
      enabled: true               # 是否开启多线程并行搜索
      threads: 4                  # 实例级最大搜索线程数
      min-vector-count: 200000    # 仅低并发且需要降低单请求延迟时启用请求内并行

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

## 8. 演进路线图 (Roadmap)

### P0：正确性与资源稳定性

1. 固定 Store 级 SQ8 量化参数，补充动态数据范围、重建和 Recall 回归测试。
2. 将堆外 Buffer 改为分段存储或可预测预分配，消除整块扩容峰值；启动时校验 `max-capacity`、堆、Direct Memory 预算。
3. 完成两阶段 Upsert、失败回滚、`assertConsistency`、Store 关闭释放文件/Direct 资源。
4. 对查询设置 deadline、最大并发和有界队列；饱和时快速拒绝，禁止 `CallerRunsPolicy` 将计算回压到业务线程。

**验收**：50 万容量压力不因扩容触发 OOM；故障注入后不出现 Offset 错位；资源指标可被采集和告警。

### P1：20 万精确 Flat 性能

1. 消除堆外向量到 `byte[]` 的复制，接入直接字节打分。
2. 写入时持久化归一化常量，移除循环内 `sqrt`、字符串分支和重复计算。
3. 在原生 ARM64 JDK 21 环境建立标量基线，并以 Vector API 实现可选 NEON/AVX 加速；两条路径必须保持结果一致。
4. 高并发时以“多请求并行”为主；请求内并行仅用于低并发且延迟敏感的场景。

**验收**：20 万、512 维、30 QPS 固定到达率下，P99 <= 150 ms；报告包含 P50/P95/P99、排队时间、CPU、GC、Direct Memory 和拒绝数。

### P2：生产部署与可观测性

1. 支持快照构建、预热、健康检查和新旧 Store 原子切换，避免在线重建索引。
2. 提供搜索耗时、候选数、过滤选择率、队列长度、并发数、Direct Memory、快照版本等指标。
3. 以多副本本地 Store 承载服务级 200 QPS，按照实测单实例容量配置限流和负载均衡。

**验收**：200 QPS 混合负载下服务 P99 <= 150 ms，单实例 CPU 保留 >= 30% 余量，实例失效或索引切换不造成大面积超时。

### P3：IVF 与自动路由

1. 实现 Mini-Batch K-Means、`nlist/nprobe`、桶内 SQ8 打分和 Snapshot 持久化。
2. 以有效候选数、过滤选择率和实例负载执行 `AUTO` 路由；大于 20 万有效候选数时默认 IVF。
3. 使用 Float32 精确结果做 Ground Truth，持续监控 Recall@10 和 Recall@100。

**验收**：大库场景满足 Recall@10 >= 98.5%，并在目标硬件上显著降低 P99 和 CPU；无法满足召回约束时可回退到 Flat。

### P4：后续能力

* 支持更复杂的位图表达式和稀疏位图压缩。
* 将 HNSW 作为可选插件，而不是小库默认路径。
* 基于历史负载调整 Flat、IVF、请求内并行与副本扩缩容策略。

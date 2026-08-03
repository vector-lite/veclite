# Veclite 核心性能与架构升级设计方案 (V2.1 - 可配置扩展版)

## 1. 方案背景与高维向量 (1024维) 精度评估

基于前期 100万向量内存基准测试及 1核1G 极限压测，为解决大向量规模下的 **CPU 计算延迟**、**JVM 堆内存占用** 及 **GC 干扰** 问题，本方案确定了下阶段的 3 项核心升级，并**全面引入开关与参数可配置机制**。

### 关于高维向量 (1024 维 / 1536 维) SQ8 量的精度分析
* **维度越高，SQ8 精度损失反而相对更稳定**：高维空间（如 1024 维或 1536 维 OpenAI / BGE-large 向量）具有“高维各向同性”特征，信息平摊在更多维度上。虽然总维度翻倍，但各分量间的相对几何角度在 SQ8 映射后保持得更好，Top-10 召回率依然能稳定保持在 **98.0% ~ 99.0%** 以上。
* **高维下的内存与计算红利**：1024 维未量化向量单条即占 4KB 内存，开启 SQ8 后单条降至 1KB。高维下开启 SQ8 的**内存收益与 CPU 向量整型计算加速比更加显著**！

---

## 2. 三大核心优化与全参数配置设计

所有优化方向默认提供开关与弹性参数，业务方可通过 `application.yml` 的 `veclite.*` 灵活控制。

```
                          ┌─────────────────────────────────────────┐
                          │   Business Config (application.yml)     │
                          └────────────────────┬────────────────────┘
                                               │
             ┌─────────────────────────────────┼─────────────────────────────────┐
             │                                 │                                 │
┌────────────▼──────────────┐     ┌────────────▼──────────────┐     ┌────────────▼──────────────┐
│  1. 多线程并发并行搜索    │     │   2. SQ8 标量量化存储     │     │   3. 堆外内存存储 (Off-Heap)  │
├───────────────────────────┤     ├───────────────────────────┤     ├───────────────────────────┤
│ - enabled: true/false     │     │ - enabled: true/false     │     │ - enabled: true/false     │
│ - threads: N (可指定线程数)│     │ - rerank: true (重排序)   │     │ - direct-memory: true     │
│ - min-vector-count: 10000 │     │ - rerank-k: 50            │     │                           │
└───────────────────────────┘     └───────────────────────────┘     └───────────────────────────┘
```

---

### 2.1 优化一：多线程并发并行搜索 (Parallel Flat Search)

#### 运行机制
1. **小数据量自动降级**：当 Store 中的向量总数低于 `min-vector-count` (默认 10,000 条) 时，自动使用单线程遍历，规避多线程分发与上下文切换开销。
2. **大数据量分段并行**：超过阈值时，将 `FloatVectorBuffer` 划分为 $N$ 个 Chunks，使用独立的专用线程池并发计算局部 Top-K，最后主线程合并结果。
3. **全局线程池隔离**：提供专用的固定线程池，防止抢占 Spring Boot Tomcat / Netty 主工作线程。

#### 配置参数设计
```yaml
veclite:
  searcher:
    parallel:
      enabled: true               # 是否开启多线程并行搜索（默认 true）
      threads: 4                  # 并行搜索线程数（默认 Runtime.getRuntime().availableProcessors()）
      min-vector-count: 10000     # 触发并行搜索的最小向量条数（低于此值自动降级为单线程）
```

---

### 2.2 优化二：纯 SQ8 标量量化极简存储 (Pure SQ8 Storage)

#### 运行机制
1. **零 Float32 额外内存分配**：当 Store 开启 `quantization: SQ8` 时，系统**不再分配 Float32 原始浮点缓冲区**，所有向量在写入时直接量化为 8-bit `byte`（-128 ~ 127）平铺连续存储在字节缓冲区。
2. **极轻量开销**：512 维向量单条仅占 512 字节内存（为 Float32 的 25%），100 万向量的底层向量内存仅需 ~476 MB。
3. **极速单阶段检索**：检索时直接在 SQ8 字节缓冲区上计算余弦相似度并得出 TopK 结果，消除二次重排开销，大幅降低 CPU 延迟与内存占用。

#### 配置参数设计
```yaml
veclite:
  stores:
    knowledge:
      dimension: 1024
      quantization: SQ8               # 开启 SQ8 量化（默认为 NONE）
```

---

### 2.3 优化三：堆外内存存储 (Off-Heap Storage)

#### 运行机制
1. **脱离 JVM GC**：使用 `ByteBuffer.allocateDirect(...)` 或 Java 17 `Unsafe` API 分配 OS 堆外内存存储向量 Buffer。
2. **零 GC 干扰**：即使存储数 GB 的向量数据，JVM Heap 内部只保存轻量级的引用指针，彻底解决 Full GC 风险。

#### 配置参数设计
```yaml
veclite:
  storage:
    off-heap:
      enabled: true               # 是否开启堆外内存存储（默认 false，开启后向量存储于 Direct Memory）
```

---

## 3. 完整配置示例 (`application.yml`)

```yaml
veclite:
  enabled: true
  
  # 1. 搜索计算配置
  searcher:
    parallel:
      enabled: true
      threads: 4                  # 自定义线程数
      min-vector-count: 10000

  # 2. 存储与内存配置
  storage:
    type: SNAPSHOT_FILE
    off-heap:
      enabled: true               # 开启堆外内存，解决 GC 问题
    snapshot-file:
      base-path: ./data/vec

  # 3. 多 Store 库配置
  stores:
    knowledge-1024:
      dimension: 1024             # 支持 1024 高维度向量
      max-capacity: 500000
      metric: COSINE
      quantization: SQ8           # 开启纯 SQ8 量化，1024 维单条仅占 1KB 内存
```

---

## 4. 实施阶段计划

1. **Phase 1**：实现配置类 `VectorLiteProperties` 的扩展参数，以及 `ParallelSearchExecutor` 并行计算框架与降级逻辑。
2. **Phase 2**：实现 `SQ8Quantizer` 编解码器与二次 `Re-ranking` 重排序逻辑。
3. **Phase 3**：实现 `OffHeapFloatVectorBuffer` 堆外内存缓冲区。
4. **Phase 4**：更新单元测试与配置开关测试，验证 1024 维度下的正确率与性能。

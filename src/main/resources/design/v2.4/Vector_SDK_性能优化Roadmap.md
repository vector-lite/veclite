# Vector SDK 性能优化 Roadmap (V2.4 最新演进)

## 当前状态 (已完成 V2.0 ~ V2.3)
- Flat Search 连续内存基础检索
- 纯 SQ8 量化存储与 4 倍内存压缩
- Metadata Filter 倒排 BitSet 纳秒级前置过滤
- OffHeapSQ8Buffer 堆外零 GC 向量缓冲区
- MMapPayloadStorage 磁盘延迟加载
- IntLongIdIndex 扁平数值化 ID 字典
- 完成 10w~100w 规模及 1核1G 受限环境极限性能压测

## 当前结论与核心痛点
- 10万规模单核全量 Flat 检索仅 ~30 ops/s（耗时 ~35ms），无法满足生产级低延迟与高 QPS 要求。
- **瓶颈根因**：单次检索 5,120 万次算术循环中存在大量重复模长计算、未代数展开的浮点除法加法，以及堆外 `duplicate()` 临时对象分配开销。
- **演进策略**：**优先在 P0 阶段实施“查询级数学代数展开与免反量化预计算”以及“消除循环内临时对象分配”，单核 Flat 检索 QPS 立即提升 3~4 倍至 100~130+ ops/s；随后通过 IVF 索引实现突破性的 20~50 倍飞跃。**

---

## P0（最高优先级 · 立即实施）

### 1. SQ8 查询级数学代数展开与免反量化预计算 (Direct SQ8 Precomputation)
- **定位**：单核全量 Flat 检索的核心提速引擎。
- **原理**：
  - 将反量化公式 $t_i = \text{min} + (b_i + 128) \cdot \frac{\text{range}}{255} = c_1 + c_2 b_i$ 进行代数展开；
  - 点积变换为：$\sum q_i t_i = c_1 \sum q_i + c_2 \sum q_i b_i$；
  - 在查询入口仅执行 1 次 $O(d)$ 的 `queryNormSq` 与 `querySum` 计算（耗时仅 0.2 μs），彻底消灭 10 万次遍历循环内的所有除法、加法与重复模长运算；
  - 写入侧开销为 0（主流 L2 归一化向量）或最多 0.05 μs，对 Upsert 吞吐几乎 0 影响（< 0.5%）。
- **预期收益**：单核检索效率直接提升 **300% ~ 400%**，QPS 从 30 跃升至 **100 ~ 130 ops/s**。

### 2. 消除堆外检索循环临时对象 (Zero-Allocation Buffer Scan)
- **定位**：消除微架构层面的 GC 与内存抖动。
- **原理**：重构 `OffHeapSQ8Buffer`，消除每次 `copyVectorTo` 触发的 `directBuffer.duplicate()` 与 `new byte[dim]` 分配，支持底层连续指针直接批量寻址与零拷贝扫描。
- **预期收益**：单次检索减少 10 万个临时对象分配，彻底杜绝小对象垃圾回收暂停。

### 3. Panama Vector API (SIMD 向量化并行计算)
- **定位**：CPU 底层硬件指令级加速。
- **原理**：基于 Java 17+ Panama Vector API 封装 `VectorMath`，利用 AVX2 / AVX-512 / ARM Neon 单指令并行处理 8~16 个分量。
- **预期收益**：纯数学计算环节提速 **20% ~ 40%**。

---

## P1（高优先级）

### 4. 纯 Java 自研轻量 IVF 倒排聚类索引 (`IVF_SQ8`)
- **定位**：突破 $O(N)$ 计算瓶颈的根本手段。
- **原理**：纯 Java Mini-Batch K-Means 聚类 ($K=256\sim 1024$)，检索时仅探测最近的 $nprobe=8\sim 16$ 个桶（仅扫描 3% 的向量）。
- **预期收益**：计算量骤降 97%，单核 QPS 跃升至 **600 ~ 1,500+ ops/s**，单次延迟 < 2ms。

### 5. 多核并行搜索弹性调度 (Parallel Search)
- **定位**：充分利用多核服务器硬件算力。
- **原理**：针对无属性过滤的大底库自动按 Chunk 分段多线程计算后合并 Top-K。
- **预期收益**：在 4 核/8 核生产服务器上吞吐线性放大 **3 ~ 7 倍**。

---

## P2（持续优化）

### 6. Metadata Bitmap Filter 进阶
- 支持 RoaringBitmap 稀疏压缩。
- 支持复合多条件语法树（`AND / OR / NOT / BETWEEN`）。

---

## P3（V3 插件化能力）

### 7. HNSW 插件化近似图检索
- 作为 Searcher 插件引入，适用于千万级大底库及超高并发近实时召回。

### 8. Auto Searcher 智能路由
- 根据各 Store 的实时数据规模与负载，在 Flat（100% 召回）与 IVF/HNSW 之间自动透明切换。

---

## 明确不建议投入的方向 (Non-Goals)
- ❌ 手工极端微循环指令微调（依赖 JIT 编译）。
- ❌ 分布式 Raft / 重型 WAL 日志系统。
- ❌ 复杂的 LSM-Tree Segment Merge / Compaction。

---

## 设计哲学与原则
1. **SDK 而不是数据库**：保持极致嵌入式与极简 JAR 包，轻量无侵入。
2. **纯 Java 跨平台**：坚决拒绝 C++ JNI/Native 依赖，保证 100% 跨平台与零 Crash。
3. **零写入惩罚**：所有检索加速方案严禁以大幅牺牲写入吞吐（Upsert）为代价。
4. **默认高召回**：Flat 模式保证 100% Recall，IVF 模式保证 98.5%+ 召回。

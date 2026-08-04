# Vector SDK 性能优化 Roadmap

## 当前状态
- Flat Search 已完成
- SQ8 已支持
- Metadata Bitmap Filter 已完成
- 已完成基础性能压测

## 当前结论
Flat Search 已接近理论 O(N) 性能，百万以内仍具有工程价值。后续优化应优先优化单次距离计算效率，其次减少参与计算的向量数量。

## P0（立即实施）
### 1. Panama Vector API（SIMD）
- 收益：预计20%~40%
- 封装 VectorMath 接口，方便实现切换。

### 2. SQ8 直接计算
- Query 保持 float[]
- Database 保持 byte[]
- 避免反量化，预计提升20%~50%。

### 3. TopK 小顶堆
- 保持 PriorityQueue
- 复杂度 O(N log K)

## P1（高优先级）
### 4. 多线程并行搜索
- 按 Block 切分向量
- 多线程计算后 Merge TopK
- 多核机器收益明显。

### 5. mmap 存储
- 降低 JVM Heap
- 利用 OS Page Cache
- 更快恢复
- 主要优化内存，不直接提升计算速度。

## P2（持续优化）
### Metadata Bitmap Filter（已完成）
继续优化：
- Bitmap 压缩
- 多条件 AND/OR
- 更早裁剪 Candidate

## P3（V3 插件）
### HNSW Searcher
作为 Searcher 插件引入。
适用于百万以上、高QPS场景，可接受近似召回。

### IVF Searcher
作为另一种插件实现。

## P4（长期）
### Auto Searcher
根据向量规模自动选择 Flat 或 HNSW。

## 不建议投入
- 手工微优化循环
- 数据库级 WAL
- Segment Merge
- Compaction

## 演进路线
### V2.4
- Flat
- SQ8
- Bitmap Filter
- SIMD
- 多线程
- mmap

### V2.4+
- HNSW
- IVF
- Auto Searcher
- PQ（可选）

## 设计原则
1. SDK 而不是数据库
2. 默认保证100% Recall
3. 插件化扩展高级能力
4. 保持 API 简洁

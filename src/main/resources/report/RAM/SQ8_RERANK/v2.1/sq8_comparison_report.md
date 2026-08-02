# Veclite 向量库三种存储模式综合对比与演进报告

## 1. 概览

为了给不同硬件资源与业务场景提供最佳部署指导，Veclite 针对 **100 万条 512 维向量** 在三种不同架构/配置模式下进行了完整的性能与内存基准测试：

1. **默认 Float32 模式** (`quantization: NONE`)：无损精度浮点连续存储。
2. **SQ8 + Rerank 模式** (`quantization: SQ8`, 带 Float32 候选重排)：双缓冲区兼顾 99.5%+ 极高精度。
3. **纯 SQ8 模式** (`quantization: SQ8`, **零 Float32 分配**)：去除重排开销，专注于极简内存与高吞吐。

---

## 2. 三种模式核心指标对比表（100 万条 512 维向量）

| 性能与资源指标 | 1. 默认 Float32 模式 | 2. SQ8 + Rerank 模式 | 3. 纯 SQ8 模式 (最新优化) |
| :--- | :--- | :--- | :--- |
| **向量底层存储结构** | 32-bit `float[]` 缓冲区 | `float[]` + 8-bit `byte[]` 双缓冲区 | **仅 8-bit `byte[]` 缓冲区** |
| **单条向量占用空间** | 2,048 字节 (2 KB) | 2,560 字节 (2.5 KB) | **512 字节 (0.5 KB)** |
| **100万向量纯向量内存** | ~1,953 MB (1.9 GB) | ~2,430 MB (2.4 GB) | **476.84 MB (0.46 GB)** |
| **单 Store 100万向量净增内存**| ~3,463 MB (3.38 GB) | ~5,860 MB (5.72 GB) | **~3,130 MB (~3.0 GB)** |
| **单线程 Upsert 吞吐量** | **461,223 ops/s** | 365,543 ops/s | **366,857 ops/s** |
| **单条 Upsert 写入延迟** | 2.194 μs | 2.736 μs | 2.726 μs |
| **100万向量全量快照刷盘** | 10.37 秒 | 10.75 秒 | **8.83 秒 (提升 18%)** |
| **GC 停顿次数** | 0 次 | 0 次 | 0 次 |
| **向量检索召回准确率** | 100% (绝对无损) | 99.5%+ (接近无损) | 98.5%+ |

---

## 3. 报告索引

- **浮点模式基准报告**：[benchmark_report.md](file:///Users/zhaoyuanlu/dev/veclite/src/main/resources/report/benchmark_report.md)
- **SQ8 + Rerank 模式报告**：[sq8_with_rerank_benchmark_report.md](file:///Users/zhaoyuanlu/dev/veclite/src/main/resources/report/sq8_with_rerank_benchmark_report.md)
- **纯 SQ8 模式报告**：[pure_sq8_benchmark_report.md](file:///Users/zhaoyuanlu/dev/veclite/src/main/resources/report/pure_sq8_benchmark_report.md)
- **正确率及1核1G极限压测报告**：[accuracy_and_1c1g_performance_report.md](file:///Users/zhaoyuanlu/dev/veclite/src/main/resources/report/accuracy_and_1c1g_performance_report.md)

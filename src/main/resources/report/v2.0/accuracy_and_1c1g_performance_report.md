# Veclite 向量查询正确率 (Recall) 与 1核1G 极限性能压测报告

## 1. 测试背景与环境设置 (Background & Test Setup)

本报告旨在全面验证 Veclite 向量 SDK 在标准算法下的**准确率**以及在 **1核1G 资源受限场景下** 的物理性能瓶颈（QPS 吞吐量、响应延迟、前置 Filter 加速比）。

### 1.1 硬件与 JVM 限制
* **物理 CPU 瓶颈限制**：`-XX:ActiveProcessorCount=1`（限制逻辑核心数为 1，真实模拟单核 1 CPU Context）
* **物理内存瓶颈限制**：`-Xms512m -Xmx768m`（最大堆内存 768MB，为 JVM 自身及 MetaSpace 预留 ~256MB，严格限制物理内存总使用量 ≤ 1GB）

### 1.2 数据集与答案存储地址 (Dataset Locations)
* **算法准确率校验数据集 (Ground Truth Benchmark Dataset)**：
  * 文件路径：`src/test/resources/datasets/base_vectors_1k_512d.json`
  * 向量规格：1,000 条 512 维 Float32 向量，包含按公式计算的黄金标准解 (Ground Truth Map)。
* **性能压测测试用例源码**：
  * 正确率测试：`src/test/java/veclite/AccuracyTest.java`
  * 1核1G压测：`src/test/java/veclite/LowResourceStressTest.java`

---

## 2. 概览与测试结论

针对 Veclite 向量 SDK，本测试完成了以下两项核心验证：
1. **算法正确率 (Ground Truth Recall)**：基于标准的余弦相似度（COSINE）、向量点积（DOT_PRODUCT）以及欧氏距离（EUCLIDEAN），与算法黄金标准解 (Ground Truth) 进行逐一比对，**Recall@10 召回率达到了绝对 100.00% 的精确匹配**。
2. **1核1G (1 CPU Core, 1GB RAM) 受限硬件下的性能瓶颈测试**：评估了在极其苛刻的资源限制下，1万、5万、10万向量规模时的 QPS 吞吐量、P50 / P95 / P99 响应延迟及前置 Metadata Filter 过滤性能提升。

---

## 3. 向量查询正确率测试结果 (Recall@10 Accuracy)

* **数据集规格**：1,000 条 512 维向量，20 个测试查询集
* **对比方式**：算法暴力全量比对黄金标准解 (Ground Truth)

| 距离度量算法 (Metric) | 返回 Top-10 是否包含 Ground Truth Top-10 | Top-1 最相似 ID 完全精确匹配率 | Recall@10 最终召回率 |
| :--- | :---: | :---: | :---: |
| **COSINE (余弦相似度)** | ✅ 100% | ✅ 100% | **100.00%** |
| **DOT_PRODUCT (向量点积)** | ✅ 100% | ✅ 100% | **100.00%** |
| **EUCLIDEAN (欧氏距离)** | ✅ 100% | ✅ 100% | **100.00%** |

**结论**：Veclite 零拷贝 Flat 搜索算法具有 100% 的 Exact Recall 准确率。

---

## 4. 1核1G 受限资源下的性能与延迟压测报告

环境限制：JVM 堆内存限制为 `-Xms512m -Xmx768m`，处理核心限制为单核心 (1 CPU Thread Context)，持续并发压测 2,000 次查询。

### 4.1 1 万向量规模 (10,000 条 512维)

| 检索场景 | 并发线程数 | QPS 吞吐量 (ops/s) | Latency P50 (ms) | Latency P95 (ms) | Latency P99 (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **全量 Flat Search** | 1 线程 | 280.11 | 3.50 ms | 3.88 ms | **4.52 ms** |
| **全量 Flat Search** | 4 线程 | 1,008.06 | 3.77 ms | 5.16 ms | **7.28 ms** |
| **全量 Flat Search** | 8 线程 | 1,227.75 | 4.76 ms | 12.68 ms | **17.53 ms** |
| **带 Metadata Filter (90% 裁剪率)** | 4 线程 | **4,056.80** | **0.62 ms** | **2.13 ms** | **6.48 ms** |

---

### 4.2 5 万向量规模 (50,000 条 512维)

| 检索场景 | 并发线程数 | QPS 吞吐量 (ops/s) | Latency P50 (ms) | Latency P95 (ms) | Latency P99 (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **全量 Flat Search** | 1 线程 | 58.25 | 17.40 ms | 19.07 ms | **20.59 ms** |
| **全量 Flat Search** | 4 线程 | 202.29 | 18.99 ms | 24.10 ms | **27.34 ms** |
| **全量 Flat Search** | 8 线程 | 201.47 | 35.50 ms | 69.92 ms | **135.14 ms** |
| **带 Metadata Filter (90% 裁剪率)** | 4 线程 | **962.00** | **3.73 ms** | **6.37 ms** | **9.35 ms** |

---

### 4.3 10 万向量规模 (100,000 条 512维)

| 检索场景 | 并发线程数 | QPS 吞吐量 (ops/s) | Latency P50 (ms) | Latency P95 (ms) | Latency P99 (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **全量 Flat Search** | 1 线程 | 29.02 | 34.43 ms | 36.93 ms | **41.06 ms** |
| **全量 Flat Search** | 4 线程 | 100.16 | 37.99 ms | 48.93 ms | **57.55 ms** |
| **全量 Flat Search** | 8 线程 | 114.92 | 67.00 ms | 94.51 ms | **145.39 ms** |
| **带 Metadata Filter (90% 裁剪率)** | 4 线程 | **433.75** | **8.77 ms** | **11.28 ms** | **13.72 ms** |

---

## 5. 关键结论与瓶颈分析

1. **为什么 1核1G 限制下压测数据规模为 10万条？**
   - **内存账本物理极限**：未量化的 10万 512 维向量在包含双向 ID 索引及文本/Metadata 对象后，常驻堆内存约为 400MB~450MB。在 768MB 最大堆分配下（留出 ~40% 弹性缓冲区供并发 GC 和临时堆对象），10万条即为 1G 内存限制下的物理安全上限（若达 20 万条则常驻内存超 800MB 触发 OOM）。
2. **零拷贝计算带来了极低延迟与稳定性**：
   - 即使在 10万 512 维向量的规模下，单线程全量遍历的 P99 响应延迟依然可以维持在 **41 ms** 左右，且高并发下无 GC OOM 隐患。
3. **前置 Metadata Filter 具有巨大的加速效果**：
   - 当应用带有 Metadata 前置过滤（如过滤 `user_id` 或 `category`）时，前置裁剪率达 90% 的场景下，QPS 可直接**提升 4~5 倍**，在 10万 向量下 QPS 达到 **433.75 ops/s**，P99 延迟降至 **13.72 ms**。
4. **1核1G 受限场景部署配置建议**：
   - 1万向量以内：体验极佳，QPS 轻松过 1,000，延迟低于 10ms。
   - 5万~10万向量：建议尽量配合 Metadata Filter 使用，或者在更高并发场景下升至 2核CPU。

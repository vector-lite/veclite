# 测试规范

## 分层

- 单元测试覆盖 `VectorMath`、`SQ8Quantizer`、Buffer、ID 索引、位图过滤和边界校验。
- 集成测试覆盖 `LocalVectorStore`、客户端、快照恢复、MMap Payload、Embedding 和 Spring 自动配置。
- 准确率测试以 Float32/数学实现为基准真值（Ground Truth），验证三种度量、Recall@K 和 SQ8 预计算一致性。
- 基准/压力测试包括 `V24ComprehensiveBenchmarkTest`、`V24ResourcePerformanceBenchmarkTest`、低资源和内存测试，默认选择性运行。

测试必须覆盖正常、空值、非法输入、容量边界、更新、删除和恢复；随机数据使用固定种子；缺陷必须配套回归测试。

## 验证顺序

```bash
./gradlew test --tests 'veclite.受影响测试类'
./gradlew test
./gradlew v24ResourceBenchmark -PbenchmarkScale=...
```

压测记录数据规模、维度、度量、量化、QPS、P50/P95/P99、CPU、Heap、Direct Memory、过滤选择率和 Recall@K，不得将单机结果泛化为固定 SLO。

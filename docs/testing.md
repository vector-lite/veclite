# 测试规范

## 测试分层与标签约定（JUnit 5 `@Tag`）

所有测试类必须按职责打上对应的 JUnit 5 `@Tag` 标签，严禁在默认构建任务中混合执行耗时长或大产物的测试：

| 测试分类 | JUnit 5 标签 | 对应 Gradle 任务 | 职责范围与要求 |
| :--- | :--- | :--- | :--- |
| **单元与回归测试** | *(无特殊Tag)* | `./gradlew test` | 覆盖 `VectorMath`、`SQ8Quantizer`、Buffer、ID 索引、位图过滤、增删查改和边界校验；必须秒级执行，不落盘产生大文件。 |
| **性能基准测试** | `@Tag("benchmark")` | `./gradlew benchmark` | 评估 SQ8、预计算、并行扫描及各架构版本的检索 QPS、吞吐与内存占用，生成 Markdown 基准报告。 |
| **压力测试** | `@Tag("stress")` | `./gradlew stressTest` | 模拟 1核1G 受限资源或 10w~100w 向量高负荷并发写入与检索压测。 |
| **准确率测试** | `@Tag("accuracy")` | `./gradlew accuracyTest` | 以 Float32 / 数学实现为基准真值（Ground Truth），验证 COSINE/DOT/L2 度量下的 100% 召回率与计算一致性。 |
| **手动/外部联调** | `@Tag("manual")` | `./gradlew test -Des.manual.enabled=true --tests ...` | 依赖外部服务（如 Ollama、Elasticsearch）的集成联调，默认不运行。 |

## 新建测试规范

1. **新建测试类命名**：必须统一命名为 `*Test.java`。
2. **必须标记分类**：如果新建的测试属于 Benchmark、Stress、Accuracy 或 Manual，**类上必须添加对应的 `@Tag("...")` 注解**，确保不会污染常规 `./gradlew test` 执行。
3. **覆盖度要求**：测试必须覆盖正常、空值、非法输入、容量边界、更新、删除和恢复；随机数据必须使用固定随机种子；缺陷修复必须配套回归测试。

## 常用测试命令

```bash
# 1. 运行单个受影响测试类
./gradlew test --tests 'veclite.LocalVectorStoreTest'

# 2. 运行快速单元测试套件（日常提交前必跑）
./gradlew test

# 3. 运行性能基准测试套件
./gradlew benchmark

# 4. 运行压力测试套件
./gradlew stressTest

# 5. 运行准确率与召回率校验
./gradlew accuracyTest

# 6. 运行单核 1GB 预算资源压测
./gradlew v24ResourceBenchmark -PbenchmarkScale=...
```

压测记录数据规模、维度、度量、量化、QPS、P50/P95/P99、CPU、Heap、Direct Memory、过滤选择率和 Recall@K，不得将单机结果泛化为固定 SLO。

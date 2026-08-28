# veclite

## 项目结构与模块职责

这是一个基于 Java 17 和 Gradle 的向量存储库。生产代码位于 `src/main/java/veclite`，按职责划分：`api` 提供公共接口，`engine` 负责内存检索和存储，`model` 放置 DTO 与枚举，`quantization`、`math`、`persistence`、`config`、`embedding` 和 `web` 提供配套能力。新增类必须放入职责最匹配的包。

测试位于 `src/test/java/veclite`，测试数据位于 `src/test/resources/datasets`；禁止将生成的 Store 提交到源码仓库。运行时资源位于 `src/main/resources`，包括 `META-INF` 中的 Spring 元数据、`design` 下的版本化设计说明和 `report` 下的基准报告。

## 构建、测试与开发命令

必须在包含 `gradlew` 的 Git 项目根目录执行：

```sh
./gradlew build                 # Compile, run unit tests, and package the library
./gradlew test                  # Run fast unit & regression tests (excludes benchmark/stress/accuracy)
./gradlew test --tests 'veclite.LocalVectorStoreTest'  # Run one targeted test class
./gradlew benchmark             # Run performance benchmark suite (@Tag("benchmark"))
./gradlew stressTest            # Run 1C1G & low-resource stress tests (@Tag("stress"))
./gradlew accuracyTest          # Run Ground Truth recall & accuracy validation (@Tag("accuracy"))
./gradlew publishToMavenLocal   # Publish the 1.0.0 artifact locally
```

常规测试 `./gradlew test` 仅运行快速单元与回归测试（堆内存 512M~2GB），严禁在默认测试流中执行耗时长或生成大型本地向量快照的测试。

## 代码风格与命名规范

使用四空格缩进和标准 Java 大括号风格。遵循现有包名（如 `veclite.engine`）和类名（如 `LocalVectorStore`、`SQ8Quantizer`）。方法和字段使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`，向量维度、偏移量和指标使用表达性强的名称。公共 API 必须显式校验，测试使用 JUnit 断言而不是临时输出。项目未配置格式化工具、Lint 或覆盖率阈值，应匹配周边代码并保持差异聚焦。

## 测试规范与分类要求

使用 JUnit Jupiter（`@Test`，可选 `@DisplayName`），测试类命名为 `*Test.java`。**新建测试必须严格打上对应分类的 JUnit 5 `@Tag` 注解**：
- **单元与回归测试**：常规小规模逻辑验证，不添加特殊 Tag（默认由 `./gradlew test` 自动执行，必须秒级完成且禁止产生持久化垃圾数据）。
- **性能基准测试**：耗时评估、QPS 与内存基准，必须添加 `@Tag("benchmark")`，由 `./gradlew benchmark` 执行。
- **压力测试**：1核1G 受限或 10w~100w 规模并发压测，必须添加 `@Tag("stress")`，由 `./gradlew stressTest` 执行。
- **准确率测试**：基于 Ground Truth 数据集的召回率检验，必须添加 `@Tag("accuracy")`，由 `./gradlew accuracyTest` 执行。
- **手动/外部联调测试**：依赖外部服务的测试，必须添加 `@Tag("manual")`。

在相关测试旁增加聚焦的回归测试，覆盖正常行为、非法输入和边界条件；随机向量必须使用固定种子。先运行受影响的测试类，再运行完整测试套件。

## 提交与合并请求规范

提交信息使用简洁的类型前缀，正文采用短中文描述，例如 `feat: 优化 SQ8 预计算` 或 `fix: 修复 mmap payload 偏移`。每个提交只解决一个清晰问题。合并请求应说明行为变化、关联问题、运行的测试，以及性能或公共 API 变化所对应的基准数据或 API 证据。

## 扩展工程规则

本文件是模型工具与开发者入口规范。任何代码、测试、配置或文档修改，都必须先阅读本文件，并按任务需要继续阅读以下关联文档：

| 任务 | 文档 |
| --- | --- |
| 分层、领域建模、依赖方向、持久化边界 | [docs/architecture.md](docs/architecture.md) |
| Java 编码、API 兼容、异常和性能热路径 | [docs/coding-style.md](docs/coding-style.md) |
| 单元、集成、准确率、回归和压测 | [docs/testing.md](docs/testing.md) |
| 日志、指标、健康检查、基准报告和告警 | [docs/observability.md](docs/observability.md) |

### 工具工作流与质量门槛

1. 先检查相关源码、测试、构建脚本和设计文档，再设计修改方案。
2. 保持最小改动，禁止无关格式化、批量重命名和破坏性命令。
3. 先运行受影响的测试，再运行 `./gradlew test`；性能变化必须运行对应基准测试。
4. 交付时说明改动范围、验证命令、结果和未验证风险；不得伪造测试或指标。
5. 公共 API 的签名、默认值、异常语义和序列化格式必须保持兼容。
6. 新行为必须有测试；缺陷修复必须有回归测试；offset、召回率、线程安全和恢复能力不得被性能优化破坏。

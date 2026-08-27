# 架构与 DDD 规范

## 分层与依赖

```text
api / web → engine（应用流程与聚合协调）→ model（领域对象）
                                           ↓
                         math / quantization / persistence / embedding
```

- `api` 暴露稳定客户端接口，不泄漏 Buffer、文件句柄和内部索引。
- `engine` 管理 Store 生命周期、写入、删除、检索和一致性协调。
- `model` 放置不可变 DTO、值对象和枚举；优先使用 Java 17 `record`。
- `persistence`、Embedding Provider 以接口作为端口，具体实现隔离在基础设施侧。
- `config` 只负责 Spring 装配；`web` 不承载领域逻辑。上层依赖接口，不依赖文件、HTTP 或 Spring 细节。

## 聚合与不变量

- `VectorStore` 是核心聚合；写入、更新、删除和快照通过聚合边界协调。
- offset 由唯一分配点产生，严禁向量、Payload、ID 索引各自递增。
- 运行期采用 append-only + 软删除；物理整理必须显式全量重建。
- 快照前校验 `vectorSize == payloadSize == idIndexSize`。
- 一个 Store 的 SQ8 参数生命周期内固定；变化时重建新 Store 后原子切换。

## 变化点

度量、量化、Payload、搜索路由存在多个实现时，使用端口 + 策略 + 工厂。工厂只选择实现，策略只执行行为，优先组合和不可变对象，避免上帝类与隐式全局状态。

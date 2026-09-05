# v2.5 增量同步与对账设计（多节点部署）

## 背景与动机

文档型持久化（MONGODB/POSTGRES）采用写透模式（RPO=0）：upsert/delete 先提交真相源再更新内存。
在此前提下：

- 旧 `saveStore` 的"全量刷盘"语义失效——内存中没有真相源不知道的数据，全量重写纯属浪费；
  且 SQ8 冻结库会把真相源原始 Float32 向量覆盖为反量化近似值（精度回退）。
- 旧 `reload` 的"定时全量重载"在多节点下代价 O(N) 全表扫描 + 内存全量重建，
  且装载窗口内并发检索可见空/部分数据。

多节点部署的真实诉求是：**各节点内存投影按低成本周期收敛**。

## 方案：软删 tombstone + updatedAt 水位

### 数据面

- 文档实体新增 `deleted` 布尔标记（PG 存量表经 `ADD COLUMN IF NOT EXISTS` 幂等迁移）。
- `deleteByIds` 由物理删除改为软删除（`deleted=true` + 刷新 `updated_at`），保留 tombstone 行；
  upsert 整体替换并清除该标记（被删 docId 重新写入即复活）。
- Store 元数据新增 `sync_watermark`（增量水位）。元数据保存为部分更新语义：
  入参水位为 null 时保留库中现值，避免仅登记定义的调用方抹掉基线。

### 编排面（AbstractDocumentPersistence）

| 操作 | 语义 | 成本 |
| --- | --- | --- |
| `upsertDocuments` | 写透落原始 Float32（不变） | 增量 |
| `loadStore` | 全量重建（排除 tombstone），以**装载开始时间**写入水位基线 | O(N)，显式触发 |
| `saveStore` | 集合级对账：按 docId 集合差补缺失、软删滞留行、同步元数据；**不重写已一致文档** | O(N) ID 比对，无向量重写 |
| `incrementalSync` | 快检（`countUpdatedSince==0` 短路）→ 拉取 `updatedAt > watermark` 变更（含 tombstone）→ 内存 upsert/删除 → 水位推进到本批最大 updatedAt | 与真实增量成正比 |

### 调度面

- `veclite.engine.StoreSyncScheduler`：自持单线程（固定延迟），逐 Store 调用
  `VectorEngineClient.syncStore`，单库失败告警后继续；`veclite.storage.sync.enabled` 开启，
  默认关闭。
- tombstone 压缩：保留期 `veclite.storage.sync.retention-days`（默认 7 天，<=0 关闭）；
  对账/装载强制执行，增量同步路径按每 Store 每小时节流。

### 语义分层（公共 API）

- `reload` = 全量重建 + 水位基线（冷启动/修复）；
- `refresh` = 对账修复（运维显式触发；多节点下意味着"本节点内存为权威"，禁止定时执行）；
- `syncStore` = 增量同步（多节点定时收敛的常态通道）。

## 已知边界

- 各行 `updatedAt` 取自写入方节点时钟，跨节点时钟偏差可能跳过个别变更，生产依赖 NTP。
- `updatedAt` 为空的存量行（极老版本写入）不参与增量扫描，需一次全量 reload 兜底。
- 水位基线缺失（存量元数据未迁移）时增量同步跳过并告警，由一次显式 reload 建立基线。
- 水位基线取装载开始时间：装载窗口内其他节点的写入 updatedAt 晚于基线，
  由其后首次增量同步补齐，窗口不丢数据。

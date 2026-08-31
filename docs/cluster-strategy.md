# Veclite 接口集群化策略

> 适用范围：veclite 部署在 K8s 多 pod 集群（3 master + 6 replica）时，每个 HTTP 接口在集群中的角色与处理策略。
>
> 目标读者：veclite 维护者、平台运维。

## 集群拓扑约定

- **3 个 master pod**：可写，负责接收 create / upsert / delete / refresh
- **6 个 replica pod**：只读，负责接收 search / list / stats / reload
- **共享组件**：PostgreSQL（metadata）+ OSS（snapshot）+ 本地 mmap（缓存）

---

## 1. 创建向量库（createStore）

**接口**：`POST /veclite/api/v1/stores/{storeName}`

**集群策略**：

1. **网关路由** → 创建请求先到网关，按 storeName hash 路由到目标 master pod
2. **master 角色校验** → 当前 pod 必须是 master；replica 收到请求直接 403
3. **PG 预查** → `metadataRepository.findByName(storeName)`，已存在走幂等返回
4. **embedding 模型校验** → 模型必须在白名单，绑定后不可变
5. **写 PG** → 元数据写入 `veclite_store_meta`（先 PG 成功，再放内存；PG 失败直接抛错不进 hash）
6. **构造 LocalVectorStore** → 落到本 pod 内存 hash
7. **失败回滚** → 第 5 / 6 步异常时回滚 PG 记录
8. **通知 replica** → 写完 PG 触发 store 创建事件，replica 从 OSS 拉空 snapshot 到本地 mmap
9. **返回 SUCCESS**

**并发与一致性**：
- PG `store_name PRIMARY KEY` + `ON CONFLICT DO UPDATE` 兜底（重复请求幂等）
- 顺序：先 PG 后内存，避免 PG 没记录但内存有的不一致
- `ConcurrentHashMap.compute` 原子化进 hash

**待办**：
- [ ] 网关层 storeName → master 路由表（外部，不在 veclite 项目内）
- [x] master/replica 角色配置项（`VectorLiteProperties.NodeConfig`）
- [ ] `createStore` 入口加 PG 预查
- [ ] 调整写入顺序：先 PG 后内存
- [ ] try-catch 失败回滚
- [ ] store 创建事件通知机制

---

## 2. 新增文档（upsert）

**接口**：`POST /veclite/api/v1/stores/{storeName}/documents`

**当前逻辑（单 pod）**：
- 前端 → Controller → 内存（LocalVectorStore.upsert）
- 内存 → IdOffsetIndex / OffHeapSQ8Buffer / FloatVectorBuffer
- 磁盘 → PayloadStorage.put 同步落本地 mmap（payload 走 mmap 文件，OS 自己管 page cache）
- OSS / PG：upsert 时不参与，靠 FlushScheduler 定时刷盘时统一处理

**为什么 OSS / PG upsert 时不参与**：
- OSS 上传是异步链路（在 `refresh` / `FlushScheduler` 里），与 upsert 解耦，不阻塞写
- 单 pod 部署，没有 replica 同步需求，异步刷本地文件足够
- `OssSnapshotStorage` 类已实现，链路现成，FlushScheduler 已经会调它
- PG 只存 store 元数据 + 快照指针，不存每条文档

**集群化后流程**（4 角色）：

1. **前端** → upsert 请求
2. **内存** → master 写 `LocalVectorStore.upsert` 立即生效（本 pod 可查）
3. **磁盘** → PayloadStorage.put 同步落本地 mmap（防进程崩溃丢 payload 数据）
4. **PG** → upsert 时不参与（PG 只存 store 元数据，不存每条文档）
5. **OSS** → 异步定时刷（30s / 1000 条），不阻塞 upsert
6. **PG** → OSS 上传成功后 `updateSnapshotPointer` 写 `latestSnapshotVersion`
7. **replica** → 监听 PG 版本号变化，从 OSS 拉新 snapshot 到本地 mmap，reload 内存
8. **返回 SUCCESS**

**为什么这样**：
- upsert 高频，每次传 OSS 会爆 OSS QPS
- 本地 mmap 立即落盘 → 进程崩溃可恢复
- OSS 异步刷 → 节省带宽，攒批量
- PG 只存指针 → 不写每条文档
- replica 异步拉 → master 写完不阻塞

**待办**：
- [x] master/replica 角色配置（`veclite.node.role=master|replica`，VectorLiteProperties.NodeConfig）
- [x] upsert 入口加 master 校验（VectorLiteDebugController.rejectIfReplica）
- [x] `FlushScheduler` 触发后调 `OssSnapshotStorage.uploadSnapshot`（已有 saveStore 链路）
- [x] OSS 上传成功后 `metadataRepository.updateSnapshotPointer` 写 PG（已实现）
- [x] replica 侧新增 `ReplicaSyncScheduler`，定时查 PG 版本号对比本地
- [x] replica 监听到新版本，从 OSS 拉 snapshot 到本地 mmap + `reload`
- [ ] 按 store 分主：3 master 各管几个 store（网关层做 storeName hash）

---

## 3. 查询向量库（search）

**接口**：`POST /veclite/api/v1/stores/{storeName}/search/vector` 或 `/search/text`

**当前逻辑（单 pod）**：
- 前端 → Controller → 内存（LocalVectorStore.search）
- PG / OSS / 磁盘：主路径都不参与
- 唯一磁盘参与：MMapPayloadStorage 模式下，命中 topK 后 lazy load 文档 payload（按需读盘）
- 当前 `payload.mode=MEMORY`，所以连 lazy load 都不走，**纯内存**

**为什么 search 不需要改**：
- 向量打分在 OffHeapSQ8Buffer 内存中完成
- filter 走 MetadataFilterIndex（堆内位图）
- topK 选完直接从 PayloadStorage 取文档（堆内）
- **PG / OSS / 磁盘都不在主路径上**

**集群化后**（4 角色）：
- 前端 → 网关按 storeName 路由到任一 replica
- 内存 → replica 节点上内存里有数据就直接查（不强求最新）
- PG / OSS / 磁盘 → 不参与查询主路径
- 一致性：search 是最终一致，replica 晚 master 几秒是允许的

**待办**：
- [ ] 无（search 链路不需要改）

---

## 4. 查询文档（listDocuments）

**接口**：`GET /veclite/api/v1/stores/{storeName}/documents?page=0&size=20`

**当前逻辑（单 pod）**：
- 前端 → Controller → 内存（IdOffsetIndex + PayloadStorage）
- PG / OSS / 磁盘：不参与
- 单进程下 `synchronized upsert` 串行写，listDocuments 拿到的视图是写完的快照

**为什么单 pod 不用改**：
- 内存读，不涉及外部存储
- 单进程内读写互斥（upsert synchronized）
- 没有跨节点一致性问题

**集群化后**（4 角色）：
- 前端 → 网关按 storeName 路由到任一 replica
- 内存 → replica 节点本地读（最终一致，replica 晚 master 几秒可接受）
- PG / OSS / 磁盘：不参与查询主路径
- 读多写少场景：6 个 replica 分散读流量

**并发与一致性**：
- 多 replica 并发读同一 store：IdOffsetIndex 读安全，无锁冲突
- 读和写并发：master 写时 replica 读可能看到中间状态（最终一致代价，业务可接受）
- 跨 replica 分页：用户翻页被路由到不同 replica，顺序可能不一致——可接受
- 不要在 listDocuments 路径触发 OSS 拉取：内存没有就 503，避免拖垮响应

**待办**：
- [ ] 无（listDocuments 链路不需要改）

---

## 5. 删除文档（deleteByIds）

**接口**：`DELETE /veclite/api/v1/stores/{storeName}/documents`，body 是 `List<String>` ids

**当前逻辑（单 pod）**：
- 前端 → Controller → 内存（LocalVectorStore 内部 synchronized 临界区）
- 内存 → 软删位图 mark + 元数据位图清理
- OSS / PG：删除时均不参与，靠 FlushScheduler 定时刷盘时统一处理
- 物理回收在 compaction 时统一做

**为什么单 pod 不用改**：
- 内部 synchronized 已经兜底并发（同 store 串行）
- 软删 + 位图清理都在临界区里，原子化
- PG / OSS 异步处理

**集群化后**（4 角色）：
- 网关按 storeName 路由到 master pod
- master 校验角色（replica 收到写请求 403）
- 内存软删 + 磁盘 mmap 一起更新
- 异步：30s 定时 FlushScheduler 触发 → saveStore 双写本地 + OSS → `updateSnapshotPointer` 写 PG
- replica 监听 PG 版本号 → 拉 OSS → 本地 mmap → reload 内存

**并发与一致性**：
- 同一 store 串行删（master 上 synchronized）
- 跨 store 互不影响
- replica 内存晚 master 几秒：被删的文档在 replica 上还能被搜到几秒，最终一致
- 软删位图同步通过 OSS 快照下发到 replica

**待办**：
- [x] master/replica 角色配置（与 upsert 共用）
- [x] delete 入口加 master 校验（VectorLiteDebugController.rejectIfReplica）

---

## 6. 孤儿清理（OrphanCleanScheduler）

**触发**：定时（每天凌晨）

**思路**：
- `dropStore` 只清内存 + PG，OSS / 磁盘文件保留
- 后台 `OrphanCleanScheduler` 定时扫 PG，比对 OSS / 本地磁盘，清理孤儿

**单 pod 流程**：
1. 拉 PG 全部 `storeName` 列表
2. 列 OSS 上所有 snapshot 目录
3. 列本地 `./data/vec/` 所有 storeName 目录
4. 差集 = PG 没有但 OSS / 磁盘有的 → 孤儿
5. 对孤儿执行清理（删 OSS / 删本地目录）

**集群化（9 pod）避免重复执行**：
- 用 PG advisory lock（`pg_try_advisory_lock`）
- 9 pod 都跑扫描，但只有拿到锁的 pod 真正执行清理
- 其他 pod no-op
- 优势：
  - 单 pod / 集群用同一份代码
  - master 挂了其他 pod 能接管（锁释放后下一个拿到）
  - 不依赖"谁是 master"的角色判断

**待办**：
- [x] 新增 `OrphanCleanScheduler`，定时跑清理（已实现）
- [x] 用 PG advisory lock 防止 9 pod 重复执行（已实现）
- [x] 清理范围：OSS 上不在 PG 里的 storeName 目录 + 本地磁盘孤儿目录（已实现）

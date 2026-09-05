package veclite.persistence;

import veclite.api.VectorStoreDefinition;
import veclite.api.VectorStoreMetadata;
import veclite.engine.LocalVectorStore;
import veclite.model.ReconcileResult;
import veclite.model.StoreSyncResult;
import veclite.model.VectorDocument;
import veclite.config.VectorLiteProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档型持久化（单一真相源）的编排基类。
 * <p>
 * 职责分工：真相源持有文档（text/metadata/Float32 原始向量），内存 Store 是它的可重建投影；
 * 量化结构（SQ8 buffer 及其冻结参数）是运行时派生物，文档本体不入真相源为量化格式。
 * 编排逻辑（写透、整库装载、对账、增量同步）与具体存储无关，只依赖
 * {@link VectorDocumentRepository} 端口，因此在这里实现一次，由各存储子类提供仓储即可。
 * <ul>
 *   <li><b>写透</b>（{@link #upsertDocuments}）：写路径先提交真相源（RPO=0），成功后再更新内存；</li>
 *   <li><b>整库装载</b>（{@link #loadStore}）：重置内存后从真相源游标式全量重建，
 *       并以装载开始时间建立增量同步水位基线；</li>
 *   <li><b>集合级对账</b>（{@link #reconcileStore}）：以内存为权威按 docId 集合差修复真相源
 *       （补缺失文档、软删滞留行），<b>不重写已一致的文档</b>——SQ8 库因此不会把真相源中的
 *       原始 Float32 向量覆盖为反量化近似值；仅对真相源缺失的文档以内存可得值（SQ8 库为
 *       反量化结果，属修复场景下的最优可得近似）落库；</li>
 *   <li><b>增量同步</b>（{@link #incrementalSync}）：按元数据中的水位拉取 updatedAt 晚于水位
 *       的变更（含软删 tombstone）应用到内存并推进水位，供多节点部署下定时收敛内存投影，
 *       代价与真实增量成正比而非全库规模。</li>
 * </ul>
 */
public abstract class AbstractDocumentPersistence implements DocumentBackedPersistence {

    private static final Logger log = LoggerFactory.getLogger(AbstractDocumentPersistence.class);

    /** 批处理尺寸（对账差集删除、增量删除应用），限制高维向量复制产生的瞬时堆内存 */
    private static final int BATCH_SIZE = 1000;

    /** 增量同步热路径上 tombstone 清理的最小间隔（每 Store 节流） */
    private static final long PURGE_MIN_INTERVAL_MILLIS = Duration.ofHours(1).toMillis();

    private final VectorDocumentRepository repository;
    private final VectorLiteProperties properties;

    /** 每 Store 上次 tombstone 清理时间（毫秒），用于增量同步路径的节流 */
    private final Map<String, Long> lastPurgeAtMillis = new ConcurrentHashMap<>();

    protected AbstractDocumentPersistence(VectorDocumentRepository repository,
                                          VectorLiteProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    protected VectorDocumentRepository repository() {
        return repository;
    }

    protected VectorLiteProperties properties() {
        return properties;
    }

    /**
     * 集合级对账：以内存有效文档集合为权威修复真相源漂移——把真相源缺失的文档补齐，
     * 软删真相源中内存已不存在的滞留行，最后同步 Store 元数据，返回对账 diff 明细
     * （两个方向的修复条数、样本 ID 与耗时，供管理侧展示）。
     * 已在双方一致的文档不做任何写入，因此 SQ8 Store 的真相源原始 Float32 向量不会被
     * 反量化近似值覆盖；对账不比对文档内容，内容级漂移用 {@link #loadStore} 全量重建修复。
     * 多节点部署下对账意味着"本节点内存为权威"，应由运维显式触发而非定时执行。
     */
    @Override
    public synchronized ReconcileResult reconcileStore(LocalVectorStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        long startedAt = System.currentTimeMillis();
        String storeName = store.getDefinition().getStoreName();
        repository.ensureStore(storeName);
        int memoryActiveCount = store.getActiveCount();
        Set<String> memoryIds = collectActiveDocumentIds(store);

        Set<String> truthIds = new HashSet<>(repository.listDocumentIds(storeName));
        int staleDeleted = 0;
        List<String> staleSamples = new ArrayList<>();
        List<String> staleIds = new ArrayList<>(BATCH_SIZE);
        for (String truthId : truthIds) {
            if (!memoryIds.contains(truthId)) {
                staleIds.add(truthId);
                if (staleSamples.size() < ReconcileResult.MAX_SAMPLES) {
                    staleSamples.add(truthId);
                }
                if (staleIds.size() >= BATCH_SIZE) {
                    repository.deleteByIds(storeName, staleIds);
                    staleDeleted += staleIds.size();
                    staleIds.clear();
                }
            }
        }
        if (!staleIds.isEmpty()) {
            repository.deleteByIds(storeName, staleIds);
            staleDeleted += staleIds.size();
        }

        List<String> missingSamples = new ArrayList<>();
        int repaired = upsertMissingDocuments(store, storeName, truthIds, missingSamples);
        saveStoreMetadata(store);
        purgeExpiredTombstones(storeName, true);
        long durationMillis = System.currentTimeMillis() - startedAt;
        log.info("Reconciled store [{}]: {} missing documents upserted, {} stale documents soft-deleted in {} ms",
                storeName, repaired, staleDeleted, durationMillis);
        // listDocumentIds 只返回未软删行，truthIds.size() 即对账前真相源有效条数；
        // 对账后有效条数 = 原有效 - 滞留软删 + 缺失补齐
        int truthActiveCount = truthIds.size() - staleDeleted + repaired;
        return ReconcileResult.of(memoryActiveCount, truthActiveCount,
                repaired, staleDeleted, missingSamples, staleSamples, durationMillis);
    }

    /**
     * 整库装载：重置内存 Store 后，从真相源游标式重建全部文档（软删除行不参与重建），
     * 并以<b>装载开始时间</b>写入增量同步水位基线——装载窗口内其他节点的写入 updatedAt
     * 晚于基线，由其后首次增量同步补齐。
     * SQ8 冻结参数（若存在）先于任何文档写入注入 {@code restoreFrozenParams}，
     * 使 SQ8 文档走 {@code restoreDocumentWithSQ8} 直通路径、FLOAT32 文档走常规量化写入路径。
     */
    @Override
    public synchronized void loadStore(LocalVectorStore store) {
        if (store == null) {
            return;
        }
        String storeName = store.getDefinition().getStoreName();
        repository.ensureStore(storeName);
        int dimension = store.getDefinition().getDimension();
        Instant loadStartedAt = Instant.now();

        VectorStoreMetadata metadata = repository.findStoreMetadata(storeName).orElse(null);
        if (metadata != null && metadata.getDimension() != dimension) {
            throw new IllegalStateException("Document persistence dimension mismatch for store [" + storeName
                    + "]. Expected: " + dimension + ", found: " + metadata.getDimension());
        }
        // reset 会连同 SQ8 冻结参数一起清空，因此参数注入必须放在 reset 之后
        store.reset();
        if (metadata != null && metadata.getSq8MinPerDim() != null && metadata.getSq8ScalePerDim() != null) {
            if (!store.isSQ8Enabled()) {
                throw new IllegalStateException("Store [" + storeName + "] has frozen SQ8 params in persistence "
                        + "but is not SQ8 enabled");
            }
            store.restoreFrozenParams(metadata.getSq8MinPerDim(), metadata.getSq8ScalePerDim());
        }

        int restored = 0;
        Iterator<VectorDocumentEntity> cursor = repository.scan(storeName);
        while (cursor.hasNext()) {
            VectorDocumentEntity entity = cursor.next();
            if (entity.getSq8Vector() != null) {
                if (!store.isSQ8Frozen()) {
                    throw new IllegalStateException("Store [" + storeName + "] persisted SQ8 document ["
                            + entity.getDocId() + "] but frozen params are missing");
                }
                store.restoreDocumentWithSQ8(toDocument(entity), entity.getSq8Vector());
            } else {
                if (entity.getVector() == null || entity.getVector().length != dimension) {
                    throw new IllegalStateException("Persisted vector dimension mismatch for store [" + storeName
                            + "] document [" + entity.getDocId() + "]. Expected: " + dimension
                            + ", actual: " + (entity.getVector() != null ? entity.getVector().length : 0));
                }
                store.upsert(toDocument(entity));
            }
            restored++;
        }

        // 元数据回写：activeCount + 水位基线；真相源尚无元数据时按当前定义补建（首次装载）
        VectorStoreMetadata toSave = metadata != null
                ? metadata
                : VectorStoreMetadata.fromDefinition(store.getDefinition());
        toSave.setActiveCount(store.getActiveCount());
        toSave.setSyncWatermark(loadStartedAt);
        repository.saveStoreMetadata(toSave);
        purgeExpiredTombstones(storeName, true);
        log.info("Loaded store [{}] from truth source: {} documents restored, sync watermark baseline set to {}",
                storeName, restored, loadStartedAt);
    }

    /**
     * 增量同步：从真相源拉取 updatedAt 晚于水位（元数据 {@code syncWatermark}）的变更——
     * upsert 行写入内存、tombstone 行从内存删除——然后把水位推进到本批最大 updatedAt。
     * 面向多节点部署的定时收敛：每 tick 成本与真实增量成正比，不重置内存、并发检索无感知。
     * <p>
     * 水位缺失（存量元数据未建立基线）时不做隐式全量重建，返回空结果并由日志提示先执行一次
     * {@link #loadStore}；各行 updatedAt 取自写入方节点时钟，跨节点时钟偏差可能造成个别变更
     * 被跳过，生产环境依赖 NTP。
     */
    @Override
    public synchronized StoreSyncResult incrementalSync(LocalVectorStore store) {
        if (store == null) {
            return StoreSyncResult.empty(null);
        }
        String storeName = store.getDefinition().getStoreName();
        repository.ensureStore(storeName);
        VectorStoreMetadata metadata = repository.findStoreMetadata(storeName).orElse(null);
        Instant watermark = metadata != null ? metadata.getSyncWatermark() : null;
        if (watermark == null) {
            log.warn("Skip incremental sync for store [{}]: no sync watermark baseline, "
                    + "trigger reload once to establish it", storeName);
            return StoreSyncResult.empty(null);
        }
        if (repository.countUpdatedSince(storeName, watermark) == 0) {
            log.debug("Incremental sync for store [{}] short-circuited: no changes since watermark {}",
                    storeName, watermark);
            return StoreSyncResult.empty(watermark);
        }

        int dimension = store.getDefinition().getDimension();
        int appliedUpserts = 0;
        int appliedDeletes = 0;
        Instant maxSeen = watermark;
        List<String> pendingDeleteIds = new ArrayList<>(BATCH_SIZE);
        Iterator<VectorDocumentEntity> cursor = repository.scanUpdatedSince(storeName, watermark);
        while (cursor.hasNext()) {
            VectorDocumentEntity entity = cursor.next();
            if (entity.getUpdatedAt() != null && entity.getUpdatedAt().isAfter(maxSeen)) {
                maxSeen = entity.getUpdatedAt();
            }
            if (entity.isDeleted()) {
                pendingDeleteIds.add(entity.getDocId());
                if (pendingDeleteIds.size() >= BATCH_SIZE) {
                    store.deleteByIds(pendingDeleteIds);
                    appliedDeletes += pendingDeleteIds.size();
                    pendingDeleteIds.clear();
                }
                continue;
            }
            applyIncrementalDocument(store, storeName, entity, dimension);
            appliedUpserts++;
        }
        if (!pendingDeleteIds.isEmpty()) {
            store.deleteByIds(pendingDeleteIds);
            appliedDeletes += pendingDeleteIds.size();
        }

        metadata.setActiveCount(store.getActiveCount());
        metadata.setSyncWatermark(maxSeen);
        repository.saveStoreMetadata(metadata);
        purgeExpiredTombstones(storeName, false);
        if (appliedUpserts + appliedDeletes > 0) {
            log.info("Incremental sync for store [{}] applied {} upserts and {} deletes, watermark advanced {} -> {}",
                    storeName, appliedUpserts, appliedDeletes, watermark, maxSeen);
        } else {
            // 计数与游标之间发生清理竞态等极端情况：无事可做，降级为 debug 避免空转刷屏
            log.debug("Incremental sync for store [{}] found no applicable changes since watermark {}",
                    storeName, watermark);
        }
        return new StoreSyncResult(appliedUpserts, appliedDeletes, maxSeen);
    }

    /** 应用单条增量文档：SQ8 直通或维度校验后常规写入（语义与 {@link #loadStore} 一致） */
    private void applyIncrementalDocument(LocalVectorStore store, String storeName,
                                          VectorDocumentEntity entity, int dimension) {
        if (entity.getSq8Vector() != null) {
            if (!store.isSQ8Frozen()) {
                throw new IllegalStateException("Store [" + storeName + "] persisted SQ8 document ["
                        + entity.getDocId() + "] but frozen params are missing");
            }
            store.restoreDocumentWithSQ8(toDocument(entity), entity.getSq8Vector());
            return;
        }
        if (entity.getVector() == null || entity.getVector().length != dimension) {
            throw new IllegalStateException("Persisted vector dimension mismatch for store [" + storeName
                    + "] document [" + entity.getDocId() + "]. Expected: " + dimension
                    + ", actual: " + (entity.getVector() != null ? entity.getVector().length : 0));
        }
        store.upsert(toDocument(entity));
    }

    @Override
    public synchronized void deleteStore(String storeName) {
        repository.dropStore(storeName);
        repository.deleteStoreMetadata(storeName);
    }

    /**
     * 文档写透：真相源先提交（FLOAT32 原始向量），由调用方在真相源提交成功后再更新内存。
     */
    @Override
    public void upsertDocuments(LocalVectorStore store, List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        String storeName = store.getDefinition().getStoreName();
        List<VectorDocumentEntity> entities = new ArrayList<>(documents.size());
        for (VectorDocument document : documents) {
            if (document == null || document.getId() == null) {
                throw new IllegalArgumentException("Document and Document ID must not be null");
            }
            if (document.getVector() == null) {
                throw new IllegalArgumentException("Document [" + document.getId()
                        + "] must carry a vector before write-through persistence");
            }
            entities.add(VectorDocumentEntity.float32(document.getId(), document.getText(),
                    document.getMetadata(), document.getVector()));
        }
        repository.upsertBatch(storeName, entities);
    }

    @Override
    public void deleteDocuments(String storeName, List<String> documentIds) {
        repository.deleteByIds(storeName, documentIds);
    }

    @Override
    public void saveStoreMetadata(LocalVectorStore store) {
        repository.ensureStore(store.getDefinition().getStoreName());
        repository.saveStoreMetadata(buildMetadata(store));
    }

    @Override
    public List<VectorStoreMetadata> listStoreMetadata() {
        return repository.listStoreMetadata();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 文档型后端的 Store 注册表即真相源元数据集合，直接由仓储列出。
     */
    @Override
    public List<String> listStoreNames() {
        List<String> names = new ArrayList<>();
        for (VectorStoreMetadata metadata : repository.listStoreMetadata()) {
            names.add(metadata.getStoreName());
        }
        return names;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从真相源元数据恢复 Store 定义；不存在时返回 null，由调用方决定是否跳过。
     */
    @Override
    public VectorStoreDefinition loadStoreDefinition(String storeName) {
        return repository.findStoreMetadata(storeName)
                .map(VectorStoreMetadata::toDefinition)
                .orElse(null);
    }

    /** 释放底层连接（Spring 容器销毁时自动推断调用）。{@link VectorPersistenceStorage} 端口未声明 close，由实现暴露 */
    public void close() {
        repository.close();
    }

    /** 收集内存 Store 的有效文档 ID（对账基准集合，不做向量复制） */
    private Set<String> collectActiveDocumentIds(LocalVectorStore store) {
        int totalCount = store.getVectorBufferSize();
        Set<String> ids = new HashSet<>(store.getActiveCount());
        for (int offset = 0; offset < totalCount; offset++) {
            if (store.isOffsetDeleted(offset)) {
                continue;
            }
            LocalVectorStore.DocumentPayload payload = store.getDocumentPayloadAt(offset);
            if (payload != null) {
                ids.add(payload.getId());
            }
        }
        return ids;
    }

    /**
     * 补齐真相源缺失的文档（集合差修复）：SQ8 冻结态从内存反量化为 Float32 落库，
     * 属修复场景下的最优可得近似；双方一致的文档不经过此路径，原始向量不受影响。
     *
     * @return 实际补齐落库的文档数
     */
    private int upsertMissingDocuments(LocalVectorStore store, String storeName, Set<String> truthIds,
                                       List<String> missingSamples) {
        int dimension = store.getDefinition().getDimension();
        int totalCount = store.getVectorBufferSize();
        int repaired = 0;
        List<VectorDocumentEntity> batch = new ArrayList<>();
        float[] tempVector = new float[dimension];
        for (int offset = 0; offset < totalCount; offset++) {
            if (store.isOffsetDeleted(offset)) {
                continue;
            }
            LocalVectorStore.DocumentPayload payload = store.getDocumentPayloadAt(offset);
            if (payload == null || truthIds.contains(payload.getId())) {
                continue;
            }
            if (missingSamples.size() < ReconcileResult.MAX_SAMPLES) {
                missingSamples.add(payload.getId());
            }
            store.copyVectorFromBuffer(offset, tempVector);
            batch.add(VectorDocumentEntity.float32(payload.getId(), payload.getText(), payload.getMetadata(),
                    tempVector.clone()));
            repaired++;
            if (batch.size() >= BATCH_SIZE) {
                repository.upsertBatch(storeName, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            repository.upsertBatch(storeName, batch);
        }
        return repaired;
    }

    /**
     * 软删除行按保留期物理清理（tombstone 压缩）。{@code force=false} 时按每 Store 节流执行，
     * 供增量同步热路径调用；对账/装载走 force=true。
     * retentionDays &lt;= 0 时关闭清理。
     */
    private void purgeExpiredTombstones(String storeName, boolean force) {
        int retentionDays = properties().getStorage().getSync().getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        Long last = lastPurgeAtMillis.get(storeName);
        if (!force && last != null && nowMillis - last < PURGE_MIN_INTERVAL_MILLIS) {
            return;
        }
        Instant cutoff = Instant.ofEpochMilli(nowMillis).minus(Duration.ofDays(retentionDays));
        long purged = repository.purgeSoftDeletedBefore(storeName, cutoff);
        if (purged > 0) {
            log.info("Purged {} expired tombstones for store [{}] (retention {} days)", purged, storeName, retentionDays);
        }
        lastPurgeAtMillis.put(storeName, nowMillis);
    }

    private VectorStoreMetadata buildMetadata(LocalVectorStore store) {
        VectorStoreMetadata metadata = VectorStoreMetadata.fromDefinition(store.getDefinition());
        metadata.setActiveCount(store.getActiveCount());
        if (store.isSQ8Enabled() && store.isSQ8Frozen()) {
            metadata.setSq8MinPerDim(store.getSQ8MinPerDim());
            metadata.setSq8ScalePerDim(store.getSQ8ScalePerDim());
        }
        return metadata;
    }

    private VectorDocument toDocument(VectorDocumentEntity entity) {
        VectorDocument document = new VectorDocument();
        document.setId(entity.getDocId());
        document.setText(entity.getText());
        document.setMetadata(entity.getMetadata());
        if (entity.getVector() != null) {
            document.setVector(entity.getVector());
        } else {
            // SQ8 文档的向量由 restoreDocumentWithSQ8 直接注入量化字节，绕过反量化往返
            document.setVector(new float[0]);
        }
        return document;
    }
}

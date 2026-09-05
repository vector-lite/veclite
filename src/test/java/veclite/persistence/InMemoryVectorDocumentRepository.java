package veclite.persistence;

import veclite.api.VectorStoreMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 测试用内存版 {@link VectorDocumentRepository}：语义与 Mongo/PG 适配器对齐——
 * 软删除 tombstone、upsert 复活、updatedAt 写入时盖章、元数据水位为 null 时保留现值。
 * 时钟可注入，便于增量同步的水位推进断言。
 */
public class InMemoryVectorDocumentRepository implements VectorDocumentRepository {

    /** 可注入时钟；默认取系统时钟 */
    public Supplier<Instant> clock = Instant::now;

    /** upsert 批次调用计数（对账"不重写已一致文档"断言用） */
    public final AtomicLong upsertBatchCalls = new AtomicLong();

    private final Map<String, VectorStoreMetadata> metadataByStore = new HashMap<>();
    private final Map<String, NavigableMap<String, VectorDocumentEntity>> documentsByStore = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    private NavigableMap<String, VectorDocumentEntity> table(String storeName) {
        return documentsByStore.computeIfAbsent(storeName, name -> new TreeMap<>());
    }

    @Override
    public void ensureSchema() {
    }

    @Override
    public synchronized void upsertBatch(String storeName, List<VectorDocumentEntity> entities) {
        upsertBatchCalls.incrementAndGet();
        NavigableMap<String, VectorDocumentEntity> table = table(storeName);
        for (VectorDocumentEntity entity : entities) {
            entity.setUpdatedAt(clock.get());
            entity.setDeleted(false);
            // 整体替换语义：与 Mongo replaceOne / PG ON CONFLICT 一致，复活被软删除的 docId
            table.put(entity.getDocId(), entity);
        }
    }

    @Override
    public synchronized long deleteByIds(String storeName, List<String> documentIds) {
        NavigableMap<String, VectorDocumentEntity> table = table(storeName);
        long marked = 0;
        for (String docId : documentIds) {
            VectorDocumentEntity entity = table.get(docId);
            if (entity != null && !entity.isDeleted()) {
                entity.setDeleted(true);
                entity.setUpdatedAt(clock.get());
                marked++;
            }
        }
        return marked;
    }

    @Override
    public synchronized long deleteAll(String storeName) {
        NavigableMap<String, VectorDocumentEntity> table = table(storeName);
        long removed = table.size();
        table.clear();
        return removed;
    }

    @Override
    public synchronized Iterator<VectorDocumentEntity> scan(String storeName) {
        return snapshot(table(storeName), entity -> !entity.isDeleted());
    }

    @Override
    public synchronized Iterator<VectorDocumentEntity> scanUpdatedSince(String storeName, Instant watermark) {
        return snapshot(table(storeName), entity -> entity.getUpdatedAt() != null
                && entity.getUpdatedAt().isAfter(watermark));
    }

    @Override
    public synchronized long countUpdatedSince(String storeName, Instant watermark) {
        return table(storeName).values().stream()
                .filter(entity -> entity.getUpdatedAt() != null && entity.getUpdatedAt().isAfter(watermark))
                .count();
    }

    @Override
    public synchronized long purgeSoftDeletedBefore(String storeName, Instant cutoff) {
        NavigableMap<String, VectorDocumentEntity> table = table(storeName);
        List<String> expired = new ArrayList<>();
        for (VectorDocumentEntity entity : table.values()) {
            if (entity.isDeleted() && entity.getUpdatedAt() != null && entity.getUpdatedAt().isBefore(cutoff)) {
                expired.add(entity.getDocId());
            }
        }
        expired.forEach(table::remove);
        return expired.size();
    }

    @Override
    public synchronized List<String> listDocumentIds(String storeName) {
        List<String> ids = new ArrayList<>();
        for (VectorDocumentEntity entity : table(storeName).values()) {
            if (!entity.isDeleted()) {
                ids.add(entity.getDocId());
            }
        }
        return ids;
    }

    @Override
    public synchronized long count(String storeName) {
        return table(storeName).size();
    }

    @Override
    public synchronized void saveStoreMetadata(VectorStoreMetadata metadata) {
        VectorStoreMetadata existing = metadataByStore.get(metadata.getStoreName());
        if (existing != null && metadata.getSyncWatermark() == null) {
            // 与 Mongo $set / PG COALESCE 一致：入参无水位时保留库中现值
            metadata.setSyncWatermark(existing.getSyncWatermark());
        }
        if (existing != null && metadata.getCreatedAt() == null) {
            metadata.setCreatedAt(existing.getCreatedAt());
        }
        metadataByStore.put(metadata.getStoreName(), metadata);
    }

    @Override
    public synchronized Optional<VectorStoreMetadata> findStoreMetadata(String storeName) {
        return Optional.ofNullable(metadataByStore.get(storeName));
    }

    @Override
    public synchronized List<VectorStoreMetadata> listStoreMetadata() {
        return new ArrayList<>(metadataByStore.values());
    }

    @Override
    public synchronized void deleteStoreMetadata(String storeName) {
        metadataByStore.remove(storeName);
    }

    @Override
    public void close() {
    }

    /** 直接注入一行（模拟其他节点写入/滞留行），绕过 upsert 的时钟盖章 */
    public synchronized void putRaw(String storeName, VectorDocumentEntity entity) {
        table(storeName).put(entity.getDocId(), entity);
    }

    /** 读取当前行（含 tombstone），供断言真相源字节内容 */
    public synchronized Optional<VectorDocumentEntity> getRaw(String storeName, String docId) {
        return Optional.ofNullable(table(storeName).get(docId));
    }

    /** 生成单调递增时间戳：withClockAdvance 配合使用可精确控制增量窗口 */
    public Instant nextTimestamp() {
        return Instant.ofEpochSecond(0).plusSeconds(idSeq.incrementAndGet());
    }

    private Iterator<VectorDocumentEntity> snapshot(NavigableMap<String, VectorDocumentEntity> table,
                                                    java.util.function.Predicate<VectorDocumentEntity> filter) {
        List<VectorDocumentEntity> rows = new ArrayList<>();
        for (VectorDocumentEntity entity : table.values()) {
            if (filter.test(entity)) {
                rows.add(entity);
            }
        }
        return Collections.unmodifiableList(rows).iterator();
    }
}

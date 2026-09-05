package veclite.model;

import java.time.Instant;

/**
 * 单个 Store 一次增量同步的应用结果（不可变 DTO）。
 *
 * @param appliedUpserts 从真相源应用到内存的新增/更新文档数
 * @param appliedDeletes 从真相源应用到内存的删除（软删标记）文档数
 * @param watermark      同步后推进到的水位（updatedAt）；无变更时保持原水位
 */
public record StoreSyncResult(int appliedUpserts, int appliedDeletes, Instant watermark) {

    /** 无任何变更的空结果，水位保持不变 */
    public static StoreSyncResult empty(Instant watermark) {
        return new StoreSyncResult(0, 0, watermark);
    }
}

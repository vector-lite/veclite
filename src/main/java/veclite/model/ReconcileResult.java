package veclite.model;

import java.io.Serializable;
import java.util.List;

/**
 * 单个 Store 一次集合级对账的结果（不可变 DTO）。
 * <p>
 * 对账以内存有效文档集合为权威修复真相源漂移：真相源缺失的文档被补齐（upsert），
 * 真相源中内存已不存在的滞留行被软删除（tombstone）。两个方向的修复条数与样本 ID
 * 供管理侧展示对账 diff；样本最多保留 {@link #MAX_SAMPLES} 条，全量 ID 不随结果返回。
 *
 * @param memoryActiveCount 对账时内存有效文档条数（对账权威方）
 * @param truthActiveCount  对账完成后真相源有效（未软删）文档条数，正常应与 memoryActiveCount 相等
 * @param missingUpserted   真相源缺失、已从内存补齐的文档条数
 * @param staleSoftDeleted  真相源滞留、已被软删除的文档条数
 * @param missingSamples    被补齐文档的 ID 样本（最多 {@link #MAX_SAMPLES} 条）
 * @param staleSamples      被软删文档的 ID 样本（最多 {@link #MAX_SAMPLES} 条）
 * @param durationMillis    对账总耗时（毫秒）
 */
public record ReconcileResult(int memoryActiveCount,
                              int truthActiveCount,
                              int missingUpserted,
                              int staleSoftDeleted,
                              List<String> missingSamples,
                              List<String> staleSamples,
                              long durationMillis) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 结果中每个方向保留的样本 ID 上限：管理侧展示足够定位问题，避免大 Store 撑爆响应体 */
    public static final int MAX_SAMPLES = 100;

    /**
     * 构造结果并对样本列表做上限截断（入参列表可以为 null，统一处理为空列表）。
     */
    public static ReconcileResult of(int memoryActiveCount, int truthActiveCount,
                                     int missingUpserted, int staleSoftDeleted,
                                     List<String> missingSamples, List<String> staleSamples,
                                     long durationMillis) {
        return new ReconcileResult(memoryActiveCount, truthActiveCount,
                missingUpserted, staleSoftDeleted,
                capped(missingSamples), capped(staleSamples), durationMillis);
    }

    private static List<String> capped(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.size() <= MAX_SAMPLES ? List.copyOf(ids) : List.copyOf(ids.subList(0, MAX_SAMPLES));
    }
}

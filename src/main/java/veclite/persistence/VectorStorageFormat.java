package veclite.persistence;

/**
 * 向量文档落库格式。
 * <ul>
 *   <li>{@link #FLOAT32}：原始 Float32 小端序列化。写透路径（upsert/delete）固定使用，
 *       保证真相源中始终持有未经量化的原始向量。</li>
 *   <li>{@link #SQ8}：SQ8 冻结态的逐向量量化字节。冻结态全量对账（reconcileStore）时使用，
 *       避免对已冻结 Store 反量化后再落库，把量化误差烧进真相源；
 *       逐维量化参数存储在 Store 元数据中，装载时经 {@code restoreFrozenParams} 注入。</li>
 * </ul>
 */
public enum VectorStorageFormat {
    FLOAT32,
    SQ8
}

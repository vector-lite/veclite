package veclite.engine;

import java.util.BitSet;

/**
 * 逻辑/软删除标记位图。
 * <p>
 * 使用高效的 BitSet 记录已删除向量的 Offset 偏移量，
 * 从而避免物理删除导致整块向量数组前移重排带来的巨大性能开销。
 */
public class DeletedBitSet {

    /** 标志位图，true 表示对应的 offset 向量已被标记删除 */
    private final BitSet bitSet = new BitSet();
    
    /** 已删除的向量计数器 */
    private int deletedCount = 0;

    /**
     * 将指定 offset 标记为逻辑删除
     */
    public synchronized void markDeleted(int offset) {
        if (!bitSet.get(offset)) {
            bitSet.set(offset);
            deletedCount++;
        }
    }

    /**
     * 取消删除标记（用于 upsert 覆盖写入已删除位置时恢复）
     */
    public synchronized void unmark(int offset) {
        if (bitSet.get(offset)) {
            bitSet.clear(offset);
            deletedCount--;
        }
    }

    /**
     * 校验指定 offset 的向量是否已被删除
     */
    public boolean isDeleted(int offset) {
        return bitSet.get(offset);
    }

    /**
     * 获取已被逻辑删除的向量总数
     */
    public int getDeletedCount() {
        return deletedCount;
    }

    public synchronized void clear() {
        bitSet.clear();
        deletedCount = 0;
    }

    public BitSet getRawBitSet() {
        return bitSet;
    }
}


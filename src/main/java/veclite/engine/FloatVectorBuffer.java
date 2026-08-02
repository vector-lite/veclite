package veclite.engine;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import veclite.math.VectorMath;

/**
 * 浮点数连续向量内存缓冲区（底层存储）。
 * <p>
 * 将多条固定维度的 float 向量连续存储在单个一维 float 数组中 (`data`)，
 * 避免为每条向量单独分配小数组对象，从而降低 JVM 堆内存碎片和 GC 压力。
 * <p>
 * 并发控制说明：
 * 采用 ReadWriteLock 保证高 QPS 读读并发并行，写写/读写互斥；
 *底层 `data` 数组采用 volatile 保证扩容引用的内存可见性。
 */
public class FloatVectorBuffer {

    /** 向量维度 (例如 768 / 1536) */
    private final int dimension;

    /** 读写共享锁（读锁共享提升 QPS，写锁互斥保证安全） */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** 读锁引用 */
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();

    /** 写锁引用 */
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    /** 连续平铺存储所有向量浮点数的一维数组（使用 volatile 保证扩容引用可见性） */
    private volatile float[] data;

    /** 当前容量（可容纳的向量条数） */
    private int capacity;

    /** 当前已写入的向量条数 */
    private int size = 0;

    /**
     * 构造浮点向量缓冲区
     *
     * @param dimension       向量维度
     * @param initialCapacity 初始容量（向量条数）
     */
    public FloatVectorBuffer(int dimension, int initialCapacity) {
        this.dimension = dimension;
        this.capacity = Math.max(initialCapacity, 16);
        this.data = new float[this.capacity * dimension];
    }

    /**
     * 获取读锁，供外部在做批量遍历搜索时上锁，保证整个搜索过程中数据不发生数据竞争。
     */
    public void acquireReadLock() {
        readLock.lock();
    }

    /**
     * 释放读锁。
     */
    public void releaseReadLock() {
        readLock.unlock();
    }

    /**
     * 向 Buffer 末尾追加一条新向量。
     *
     * @param vector 维度相符的 float 数组
     * @return 返回该向量在 Buffer 中的 offset 索引下标
     */
    public int append(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("Vector dimension mismatch. Expected: " + dimension + ", actual: " + (vector != null ? vector.length : 0));
        }
        writeLock.lock();
        try {
            ensureCapacity(size + 1);
            int offset = size;
            System.arraycopy(vector, 0, data, offset * dimension, dimension);
            size++;
            return offset;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 更新指定 offset 位置上的向量（用于 upsert 覆盖更新）。
     *
     * @param offset 目标偏移量
     * @param vector 新向量数据
     */
    public void updateAt(int offset, float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("Vector dimension mismatch. Expected: " + dimension + ", actual: " + (vector != null ? vector.length : 0));
        }
        writeLock.lock();
        try {
            if (offset < 0 || offset >= size) {
                throw new IndexOutOfBoundsException("Invalid offset: " + offset + ", current size: " + size);
            }
            System.arraycopy(vector, 0, data, offset * dimension, dimension);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 复制并返回指定 offset 的向量新数组。
     */
    public float[] getVector(int offset) {
        readLock.lock();
        try {
            if (offset < 0 || offset >= size) {
                throw new IndexOutOfBoundsException("Invalid offset: " + offset + ", current size: " + size);
            }
            float[] vector = new float[dimension];
            System.arraycopy(data, offset * dimension, vector, 0, dimension);
            return vector;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 【零拷贝计算】直接以底层平铺数组在 offset 偏移位置上与 queryVector 做相似度计算。
     * 必须在外部持有读锁上下文或该调用线程持有读锁时执行。
     *
     * @param vectorMath  数学计算器
     * @param metric      度量指标
     * @param queryVector 查询向量
     * @param offset      目标向量在 Buffer 中的偏移下标
     * @return 相似度得分
     */
    public float calculateScoreZeroCopy(VectorMath vectorMath, String metric, float[] queryVector, int offset) {
        float[] rawData = this.data;
        int bOffset = offset * dimension;
        return vectorMath.calculate(metric, queryVector, rawData, bOffset, dimension);
    }

    /**
     * 将指定 offset 的向量拷贝到目标传入数组中。
     *
     * @param offset 偏移量
     * @param dest   目标复用数组
     */
    public void copyVectorTo(int offset, float[] dest) {
        readLock.lock();
        try {
            System.arraycopy(data, offset * dimension, dest, 0, dimension);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 自动扩容策略（按 1.5 倍扩容）。
     * 必须在持有 writeLock 的前提下调用。
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = capacity + (capacity >> 1);
            data = Arrays.copyOf(data, newCapacity * dimension);
            capacity = newCapacity;
        }
    }

    public int getDimension() {
        return dimension;
    }

    public int getSize() {
        readLock.lock();
        try {
            return size;
        } finally {
            readLock.unlock();
        }
    }

    public int getCapacity() {
        readLock.lock();
        try {
            return capacity;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取底层完整的原始 float[] 数组（供快照刷盘或零拷贝计算使用）。
     */
    public float[] getRawData() {
        return data;
    }
}



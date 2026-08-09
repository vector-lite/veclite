package veclite.engine;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 堆外内存 (Direct Memory) 连续 SQ8 量化字节向量缓冲区。
 * <p>
 * 使用 Java ByteBuffer.allocateDirect 在 JVM 堆外分配连续内存空间，
 * 实现十万/百万级向量 0 GC 扫描与内存占用。
 */
public class OffHeapSQ8Buffer {

    private final int dimension;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    private ByteBuffer directBuffer;
    private int capacity;
    private int size = 0;

    public OffHeapSQ8Buffer(int dimension, int initialCapacity) {
        this.dimension = dimension;
        this.capacity = Math.max(initialCapacity, 16);
        this.directBuffer = ByteBuffer.allocateDirect(this.capacity * dimension);
    }

    public int append(byte[] vectorBytes) {
        if (vectorBytes == null || vectorBytes.length != dimension) {
            throw new IllegalArgumentException("Vector bytes dimension mismatch. Expected: " + dimension + ", actual: " + (vectorBytes != null ? vectorBytes.length : 0));
        }
        writeLock.lock();
        try {
            ensureCapacity(size + 1);
            int offset = size;
            directBuffer.position(offset * dimension);
            directBuffer.put(vectorBytes);
            size++;
            return offset;
        } finally {
            writeLock.unlock();
        }
    }

    public void updateAt(int offset, byte[] vectorBytes) {
        if (vectorBytes == null || vectorBytes.length != dimension) {
            throw new IllegalArgumentException("Vector bytes dimension mismatch. Expected: " + dimension + ", actual: " + (vectorBytes != null ? vectorBytes.length : 0));
        }
        writeLock.lock();
        try {
            if (offset < 0 || offset >= size) {
                throw new IndexOutOfBoundsException("Invalid offset: " + offset + ", current size: " + size);
            }
            directBuffer.position(offset * dimension);
            directBuffer.put(vectorBytes);
        } finally {
            writeLock.unlock();
        }
    }

    public void copyVectorTo(int offset, byte[] dest) {
        readLock.lock();
        try {
            if (offset < 0 || offset >= size) {
                throw new IndexOutOfBoundsException("Invalid offset: " + offset + ", current size: " + size);
            }
            ByteBuffer duplicate = directBuffer.duplicate();
            duplicate.position(offset * dimension);
            duplicate.get(dest, 0, dimension);
        } finally {
            readLock.unlock();
        }
    }

    public byte[] getVector(int offset) {
        byte[] bytes = new byte[dimension];
        copyVectorTo(offset, bytes);
        return bytes;
    }

    /**
     * 复制所有有效向量字节到目标数组（快照刷盘用）。
     */
    public void copyAllTo(byte[] dest) {
        readLock.lock();
        try {
            ByteBuffer duplicate = directBuffer.duplicate();
            duplicate.position(0);
            duplicate.get(dest, 0, size * dimension);
        } finally {
            readLock.unlock();
        }
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = capacity + (capacity >> 1);
            if (newCapacity < minCapacity) {
                newCapacity = minCapacity;
            }
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(newCapacity * dimension);
            directBuffer.position(0);
            directBuffer.limit(size * dimension);
            newBuffer.put(directBuffer);
            directBuffer = newBuffer;
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

    public void acquireReadLock() {
        readLock.lock();
    }

    public void releaseReadLock() {
        readLock.unlock();
    }

    public ByteBuffer getDirectBuffer() {
        return directBuffer;
    }

    public void clear() {
        writeLock.lock();
        try {
            directBuffer.clear();
            size = 0;
        } finally {
            writeLock.unlock();
        }
    }
}

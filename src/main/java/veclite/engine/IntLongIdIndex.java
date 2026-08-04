package veclite.engine;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 扁平化 64-bit 数值 ID 哈希映射字典与 Offset 索引。
 * <p>
 * 替代传统基于 Map 的节点装箱模型，采用 Open-Addressing 开放寻址平铺数组 (long[] keys, int[] values)，
 * 配合一维引用数组 String[] idByOffset 实现双向高性能映射，消除 JVM 节点对象头开销。
 */
public class IntLongIdIndex {

    private static final long EMPTY_KEY = 0L;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    private long[] keys;
    private int[] values;
    private String[] idByOffset;
    private int capacity;
    private int size;
    private int mask;

    public IntLongIdIndex() {
        this(1024);
    }

    public IntLongIdIndex(int initialCapacity) {
        int cap = 16;
        while (cap < Math.max(initialCapacity, 16)) {
            cap <<= 1;
        }
        this.capacity = cap;
        this.mask = cap - 1;
        this.keys = new long[cap];
        this.values = new int[cap];
        this.idByOffset = new String[cap];
        this.size = 0;
        Arrays.fill(this.values, -1);
    }

    public static long hash64(String s) {
        if (s == null) return EMPTY_KEY;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h == EMPTY_KEY ? 1L : h;
    }

    public void put(String id, int offset) {
        if (id == null || offset < 0) {
            return;
        }
        writeLock.lock();
        try {
            ensureCapacityForPut();
            long key = hash64(id);
            int idx = (int) (key & mask);
            while (keys[idx] != EMPTY_KEY && keys[idx] != key) {
                idx = (idx + 1) & mask;
            }

            if (keys[idx] == EMPTY_KEY) {
                keys[idx] = key;
                values[idx] = offset;
                size++;
            } else {
                values[idx] = offset;
            }

            if (offset >= idByOffset.length) {
                int newLen = Math.max(offset + 1, idByOffset.length + (idByOffset.length >> 1));
                idByOffset = Arrays.copyOf(idByOffset, newLen);
            }
            idByOffset[offset] = id;
        } finally {
            writeLock.unlock();
        }
    }

    public Integer getOffset(String id) {
        if (id == null) return null;
        readLock.lock();
        try {
            long key = hash64(id);
            int idx = (int) (key & mask);
            while (keys[idx] != EMPTY_KEY) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) & mask;
            }
            return null;
        } finally {
            readLock.unlock();
        }
    }

    public String getId(int offset) {
        readLock.lock();
        try {
            if (offset >= 0 && offset < idByOffset.length) {
                return idByOffset[offset];
            }
            return null;
        } finally {
            readLock.unlock();
        }
    }

    public Integer remove(String id) {
        if (id == null) return null;
        writeLock.lock();
        try {
            long key = hash64(id);
            int idx = (int) (key & mask);
            while (keys[idx] != EMPTY_KEY) {
                if (keys[idx] == key) {
                    int offset = values[idx];
                    keys[idx] = EMPTY_KEY;
                    values[idx] = -1;
                    size--;
                    rehashCluster((idx + 1) & mask);
                    if (offset >= 0 && offset < idByOffset.length) {
                        idByOffset[offset] = null;
                    }
                    return offset;
                }
                idx = (idx + 1) & mask;
            }
            return null;
        } finally {
            writeLock.unlock();
        }
    }

    public boolean containsId(String id) {
        return getOffset(id) != null;
    }

    public int size() {
        readLock.lock();
        try {
            return size;
        } finally {
            readLock.unlock();
        }
    }

    public void clear() {
        writeLock.lock();
        try {
            Arrays.fill(keys, EMPTY_KEY);
            Arrays.fill(values, -1);
            Arrays.fill(idByOffset, null);
            size = 0;
        } finally {
            writeLock.unlock();
        }
    }

    private void ensureCapacityForPut() {
        if (size >= capacity * 0.75) {
            int newCap = capacity << 1;
            long[] oldKeys = keys;
            int[] oldValues = values;

            keys = new long[newCap];
            values = new int[newCap];
            mask = newCap - 1;
            capacity = newCap;
            Arrays.fill(values, -1);

            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY_KEY) {
                    long k = oldKeys[i];
                    int v = oldValues[i];
                    int idx = (int) (k & mask);
                    while (keys[idx] != EMPTY_KEY) {
                        idx = (idx + 1) & mask;
                    }
                    keys[idx] = k;
                    values[idx] = v;
                    size++;
                }
            }
        }
    }

    private void rehashCluster(int startIdx) {
        int idx = startIdx;
        while (keys[idx] != EMPTY_KEY) {
            long kToRehash = keys[idx];
            int vToRehash = values[idx];
            keys[idx] = EMPTY_KEY;
            values[idx] = -1;
            size--;

            int newIdx = (int) (kToRehash & mask);
            while (keys[newIdx] != EMPTY_KEY) {
                newIdx = (newIdx + 1) & mask;
            }
            keys[newIdx] = kToRehash;
            values[newIdx] = vToRehash;
            size++;

            idx = (idx + 1) & mask;
        }
    }
}

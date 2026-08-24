package veclite.engine;

import java.util.Arrays;
import java.util.Map;

/**
 * 紧凑的平铺内存 Payload 存储管理器 (Compact Payload Storage)。
 * <p>
 * 采用一维引用数组以 offset 为下标直接定位，消除 ConcurrentHashMap Node、
 * 装箱 Integer key 以及小 Map 包装节点产生的 JVM 对象头开销。
 */
public class CompactPayloadStorage implements PayloadStorage {

    private String[] ids;
    private String[] texts;
    @SuppressWarnings("unchecked")
    private Map<String, Object>[] metadatas;
    private int capacity;

    @SuppressWarnings("unchecked")
    public CompactPayloadStorage(int initialCapacity) {
        this.capacity = Math.max(initialCapacity, 1024);
        this.ids = new String[capacity];
        this.texts = new String[capacity];
        this.metadatas = new Map[capacity];
    }

    @Override
    public synchronized void put(int offset, String id, String text, Map<String, Object> metadata) {
        ensureCapacity(offset + 1);
        ids[offset] = id;
        texts[offset] = text;
        metadatas[offset] = metadata;
    }

    @Override
    public synchronized LocalVectorStore.DocumentPayload get(int offset) {
        if (offset < 0 || offset >= capacity || ids[offset] == null) {
            return null;
        }
        return new LocalVectorStore.DocumentPayload(ids[offset], texts[offset], metadatas[offset]);
    }

    @Override
    public String getId(int offset) {
        return (offset >= 0 && offset < capacity) ? ids[offset] : null;
    }

    @Override
    public String getText(int offset) {
        return (offset >= 0 && offset < capacity) ? texts[offset] : null;
    }

    @Override
    public Map<String, Object> getMetadata(int offset) {
        return (offset >= 0 && offset < capacity) ? metadatas[offset] : null;
    }

    @Override
    public synchronized void clear() {
        Arrays.fill(ids, null);
        Arrays.fill(texts, null);
        Arrays.fill(metadatas, null);
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = capacity + (capacity >> 1);
            if (newCapacity < minCapacity) {
                newCapacity = minCapacity;
            }
            ids = Arrays.copyOf(ids, newCapacity);
            texts = Arrays.copyOf(texts, newCapacity);
            metadatas = Arrays.copyOf(metadatas, newCapacity);
            capacity = newCapacity;
        }
    }
}

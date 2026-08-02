package veclite.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档业务 ID 与内存 Buffer Offset 偏移量的双向索引。
 */
public class IdOffsetIndex {

    /** 业务 ID 映射到 Buffer 内的 offset 偏移量 */
    private final Map<String, Integer> idToOffsetMap = new ConcurrentHashMap<>();
    
    /** Buffer Offset 偏移量映射回业务 ID */
    private final Map<Integer, String> offsetToIdMap = new ConcurrentHashMap<>();

    /**
     * 绑定 ID 与 Offset 映射关系
     */
    public void put(String id, int offset) {
        idToOffsetMap.put(id, offset);
        offsetToIdMap.put(offset, id);
    }

    /**
     * 根据业务 ID 查询其 Offset 偏移量
     */
    public Integer getOffset(String id) {
        return idToOffsetMap.get(id);
    }

    /**
     * 根据 Offset 偏移量反查业务 ID
     */
    public String getId(int offset) {
        return offsetToIdMap.get(offset);
    }

    /**
     * 移除指定 ID 的索引关系
     */
    public Integer remove(String id) {
        Integer offset = idToOffsetMap.remove(id);
        if (offset != null) {
            offsetToIdMap.remove(offset);
        }
        return offset;
    }

    public boolean containsId(String id) {
        return idToOffsetMap.containsKey(id);
    }

    /**
     * 获取当前已被索引的 ID 数量
     */
    public int size() {
        return idToOffsetMap.size();
    }

    public void clear() {
        idToOffsetMap.clear();
        offsetToIdMap.clear();
    }
}


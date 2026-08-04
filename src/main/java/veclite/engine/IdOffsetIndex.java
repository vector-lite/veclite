package veclite.engine;

/**
 * 文档业务 ID 与内存 Buffer Offset 偏移量的双向索引。
 * <p>
 * 底层基于 IntLongIdIndex 扁平数值映射实现，消灭 ConcurrentHashMap 与 Integer 装箱节点。
 */
public class IdOffsetIndex {

    private final IntLongIdIndex innerIndex = new IntLongIdIndex();

    /**
     * 绑定 ID 与 Offset 映射关系
     */
    public void put(String id, int offset) {
        innerIndex.put(id, offset);
    }

    /**
     * 根据业务 ID 查询其 Offset 偏移量
     */
    public Integer getOffset(String id) {
        return innerIndex.getOffset(id);
    }

    /**
     * 根据 Offset 偏移量反查业务 ID
     */
    public String getId(int offset) {
        return innerIndex.getId(offset);
    }

    /**
     * 移除指定 ID 的索引关系
     */
    public Integer remove(String id) {
        return innerIndex.remove(id);
    }

    public boolean containsId(String id) {
        return innerIndex.containsId(id);
    }

    /**
     * 获取当前已被索引的 ID 数量
     */
    public int size() {
        return innerIndex.size();
    }

    public void clear() {
        innerIndex.clear();
    }
}

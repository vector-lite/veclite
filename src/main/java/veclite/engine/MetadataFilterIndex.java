package veclite.engine;

import veclite.model.FilterExpression;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据倒排位图索引中心 (Inverted BitSet Index)。
 * <p>
 * 核心优化：
 * 1. 为 indexedFields 声明的元数据字段在写入时建立属性 ↔ BitSet 的倒排映射。
 * 2. 检索时根据 FilterExpression （支持 EQ、IN）直接生成匹配的 BitSet。
 * 3. 前置过滤直接使用位运算做在/不在判断，完全避免逐条访问 HashMap 的开销。
 */
public class MetadataFilterIndex {

    private final List<String> indexedFields;
    private final Map<String, Map<Object, BitSet>> fieldIndexes = new ConcurrentHashMap<>();

    public MetadataFilterIndex(List<String> indexedFields) {
        this.indexedFields = indexedFields != null ? indexedFields : Collections.emptyList();
    }

    /**
     * 针对单条文档的 Metadata 建立倒排位图索引。
     */
    public synchronized void indexDocument(int offset, Map<String, Object> metadata) {
        if (metadata == null || indexedFields.isEmpty()) {
            return;
        }
        for (String field : indexedFields) {
            if (metadata.containsKey(field)) {
                Object val = metadata.get(field);
                if (val != null) {
                    fieldIndexes
                        .computeIfAbsent(field, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(val, k -> new BitSet())
                        .set(offset);
                }
            }
        }
    }

    /**
     * 逻辑删除时移除文档索引。
     */
    public synchronized void removeDocument(int offset, Map<String, Object> metadata) {
        if (metadata == null || indexedFields.isEmpty()) {
            return;
        }
        for (String field : indexedFields) {
            if (metadata.containsKey(field)) {
                Object val = metadata.get(field);
                if (val != null) {
                    Map<Object, BitSet> map = fieldIndexes.get(field);
                    if (map != null) {
                        BitSet bs = map.get(val);
                        if (bs != null) {
                            bs.clear(offset);
                        }
                    }
                }
            }
        }
    }

    /**
     * 检查指定的字段是否被建立了倒排索引。
     */
    public boolean isIndexed(String field) {
        return field != null && indexedFields.contains(field);
    }

    /**
     * 根据 FilterExpression 表达式生成匹配的 BitSet（若该字段已建立倒排索引）。
     * 若无法用索引求值，返回 null，由上层降级为逐条对象属性比较。
     */
    public BitSet evaluate(FilterExpression filter) {
        if (filter == null || filter.getField() == null || !isIndexed(filter.getField())) {
            return null;
        }
        String field = filter.getField();
        FilterExpression.Operator op = filter.getOperator();

        if (op == FilterExpression.Operator.EQ) {
            return getMatchingOffsetsEq(field, filter.getValue());
        } else if (op == FilterExpression.Operator.IN) {
            return getMatchingOffsetsIn(field, filter.getValues());
        }
        return null;
    }

    /**
     * 单值 EQ 匹配的 BitSet（返回拷贝副本，保证并发安全）。
     */
    public BitSet getMatchingOffsetsEq(String field, Object value) {
        Map<Object, BitSet> map = fieldIndexes.get(field);
        if (map != null && value != null) {
            BitSet bs = map.get(value);
            if (bs != null) {
                return (BitSet) bs.clone();
            }
        }
        return new BitSet();
    }

    /**
     * 列表 IN 匹配的 BitSet：通过多 BitSet 纳秒级按位或 (Bitwise OR) 运算合成并集。
     */
    public BitSet getMatchingOffsetsIn(String field, List<Object> values) {
        BitSet result = new BitSet();
        if (values == null || values.isEmpty()) {
            return result;
        }
        Map<Object, BitSet> map = fieldIndexes.get(field);
        if (map == null) {
            return result;
        }
        for (Object val : values) {
            if (val != null) {
                BitSet bs = map.get(val);
                if (bs != null) {
                    result.or(bs); // 按位或运算合并并集
                }
            }
        }
        return result;
    }
}

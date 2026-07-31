package veclite.engine;

import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MetadataFilterIndex {

    private final List<String> indexedFields;
    private final Map<String, Map<Object, BitSet>> fieldIndexes = new ConcurrentHashMap<>();

    public MetadataFilterIndex(List<String> indexedFields) {
        this.indexedFields = indexedFields != null ? indexedFields : Collections.emptyList();
    }

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

    public BitSet getMatchingOffsets(String field, Object value) {
        Map<Object, BitSet> map = fieldIndexes.get(field);
        if (map != null) {
            BitSet bs = map.get(value);
            if (bs != null) {
                return (BitSet) bs.clone();
            }
        }
        return new BitSet();
    }
}

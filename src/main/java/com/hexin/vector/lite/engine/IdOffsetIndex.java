package com.hexin.vector.lite.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IdOffsetIndex {

    private final Map<String, Integer> idToOffsetMap = new ConcurrentHashMap<>();
    private final Map<Integer, String> offsetToIdMap = new ConcurrentHashMap<>();

    public void put(String id, int offset) {
        idToOffsetMap.put(id, offset);
        offsetToIdMap.put(offset, id);
    }

    public Integer getOffset(String id) {
        return idToOffsetMap.get(id);
    }

    public String getId(int offset) {
        return offsetToIdMap.get(offset);
    }

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

    public int size() {
        return idToOffsetMap.size();
    }

    public void clear() {
        idToOffsetMap.clear();
        offsetToIdMap.clear();
    }
}

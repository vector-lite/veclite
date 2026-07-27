package com.hexin.vector.lite.engine;

import java.util.BitSet;

public class DeletedBitSet {

    private final BitSet bitSet = new BitSet();
    private int deletedCount = 0;

    public synchronized void markDeleted(int offset) {
        if (!bitSet.get(offset)) {
            bitSet.set(offset);
            deletedCount++;
        }
    }

    public synchronized void unmark(int offset) {
        if (bitSet.get(offset)) {
            bitSet.clear(offset);
            deletedCount--;
        }
    }

    public boolean isDeleted(int offset) {
        return bitSet.get(offset);
    }

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

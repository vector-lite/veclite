package com.hexin.vector.lite.engine;

import java.util.Arrays;

public class FloatVectorBuffer {

    private final int dimension;
    private float[] data;
    private int capacity;
    private int size = 0;

    public FloatVectorBuffer(int dimension, int initialCapacity) {
        this.dimension = dimension;
        this.capacity = Math.max(initialCapacity, 16);
        this.data = new float[this.capacity * dimension];
    }

    public synchronized int append(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("Vector dimension mismatch. Expected: " + dimension + ", actual: " + (vector != null ? vector.length : 0));
        }
        ensureCapacity(size + 1);
        int offset = size;
        System.arraycopy(vector, 0, data, offset * dimension, dimension);
        size++;
        return offset;
    }

    public synchronized void updateAt(int offset, float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("Vector dimension mismatch. Expected: " + dimension + ", actual: " + (vector != null ? vector.length : 0));
        }
        if (offset < 0 || offset >= size) {
            throw new IndexOutOfBoundsException("Invalid offset: " + offset + ", current size: " + size);
        }
        System.arraycopy(vector, 0, data, offset * dimension, dimension);
    }

    public float[] getVector(int offset) {
        if (offset < 0 || offset >= size) {
            throw new IndexOutOfBoundsException("Invalid offset: " + offset + ", current size: " + size);
        }
        float[] vector = new float[dimension];
        System.arraycopy(data, offset * dimension, vector, 0, dimension);
        return vector;
    }

    public void copyVectorTo(int offset, float[] dest) {
        System.arraycopy(data, offset * dimension, dest, 0, dimension);
    }

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
        return size;
    }

    public int getCapacity() {
        return capacity;
    }

    public float[] getRawData() {
        return data;
    }
}

package veclite.engine;

import veclite.api.VectorStoreDefinition;
import veclite.config.VectorLiteProperties;
import veclite.math.PureJavaVectorMath;
import veclite.math.VectorMath;
import veclite.model.*;
import veclite.quantization.SQ8Quantizer;

import java.util.*;
import java.util.concurrent.*;

/**
 * 单个向量库（Vector Store）的核心实现类。
 * <p>
 * 负责维护：
 * 1. 向量缓冲池 (Float32 FloatVectorBuffer 或 SQ8 字节缓冲区 sq8Data)
 * 2. ID 与内部 Offset 偏移量映射 (IdOffsetIndex)
 * 3. 软删除标记位图 (DeletedBitSet)
 * 4. 倒排位图索引中心 (MetadataFilterIndex)
 * 5. 紧凑型文档 Payload 存储 (CompactPayloadStorage)
 */
public class LocalVectorStore {

    /** Store 配置定义（维度、度量方式、容量限额等） */
    private final VectorStoreDefinition definition;
    
    /** 全局配置属性 */
    private final VectorLiteProperties properties;
    
    /** 浮点模式下的连续向量存储 Buffer (SQ8模式下为 null) */
    private final FloatVectorBuffer vectorBuffer;
    
    /** 文档外部 ID ↔ 内部 Offset 偏移量的双向索引 */
    private final IdOffsetIndex idOffsetIndex;
    
    /** 软删除标志位图（避免物理删除导致全量向量重排） */
    private final DeletedBitSet deletedBitSet;
    
    /** 倒排位图索引中心 */
    private final MetadataFilterIndex metadataFilterIndex;
    
    /** 紧凑型文档 Payload 存储（平铺引用数组） */
    private final CompactPayloadStorage payloadStorage;
    
    /** 向量数学计算计算器 */
    private final VectorMath vectorMath;

    // ---- SQ8 量化字段 (SQ8模式下生效，不分配 FloatVectorBuffer) ----
    /** 是否启用 SQ8 量化 */
    private final boolean sq8Enabled;
    /** SQ8 量化的字节数据缓冲（平铺一维 byte 数组） */
    private volatile byte[] sq8Data;
    /** SQ8 缓冲区当前向量容量 */
    private int sq8Capacity;
    /** SQ8 缓冲区当前已写入向量条数 */
    private int sq8Size;
    /** 全局最小值（用于量化标定） */
    private float sq8Min = Float.MAX_VALUE;
    /** 全局最大值（用于量化标定） */
    private float sq8Max = -Float.MAX_VALUE;

    /**
     * 文档 Payload 数据承载类
     */
    public static class DocumentPayload {
        private String id;
        private String text;
        private Map<String, Object> metadata;

        public DocumentPayload(String id, String text, Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.metadata = metadata;
        }

        public String getId() { return id; }
        public String getText() { return text; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    public LocalVectorStore(VectorStoreDefinition definition) {
        this(definition, null);
    }

    public LocalVectorStore(VectorStoreDefinition definition, VectorLiteProperties properties) {
        this.definition = definition;
        this.properties = properties;
        this.idOffsetIndex = new IdOffsetIndex();
        this.deletedBitSet = new DeletedBitSet();
        this.payloadStorage = new CompactPayloadStorage(1024);
        this.metadataFilterIndex = new MetadataFilterIndex(definition.getIndexedMetadataFields());
        this.vectorMath = new PureJavaVectorMath();

        // 纯 SQ8 与 Float32 互斥分配内存
        if (definition.getQuantization() == QuantizationType.SQ8) {
            this.sq8Enabled = true;
            this.sq8Capacity = 1024;
            this.sq8Data = new byte[this.sq8Capacity * definition.getDimension()];
            this.sq8Size = 0;
            this.vectorBuffer = null;
        } else {
            this.sq8Enabled = false;
            this.vectorBuffer = new FloatVectorBuffer(definition.getDimension(), 1024);
        }
    }

    /**
     * 插入或更新向量文档 (Upsert)。
     */
    public synchronized void upsert(VectorDocument document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("Document and Document ID must not be null");
        }
        float[] vector = document.getVector();
        if (vector == null || vector.length != definition.getDimension()) {
            throw new IllegalArgumentException("Vector dimension mismatch for store [" + definition.getStoreName() + "]. Expected: " + definition.getDimension() + ", actual: " + (vector != null ? vector.length : 0));
        }

        String id = document.getId();
        Integer existingOffset = idOffsetIndex.getOffset(id);

        if (sq8Enabled) {
            // 纯 SQ8 模式：只存 8-bit 量化字节
            updateSQ8MinMax(vector);
            int dim = definition.getDimension();
            byte[] quantized = new byte[dim];
            SQ8Quantizer.quantize(vector, sq8Min, sq8Max, quantized);

            if (existingOffset != null) {
                System.arraycopy(quantized, 0, sq8Data, existingOffset * dim, dim);
                deletedBitSet.unmark(existingOffset);
                payloadStorage.put(existingOffset, id, document.getText(), document.getMetadata());
                metadataFilterIndex.indexDocument(existingOffset, document.getMetadata());
            } else {
                if (getActiveCount() >= definition.getMaxCapacity()) {
                    throw new IllegalStateException("Vector store [" + definition.getStoreName() + "] reached max capacity limit: " + definition.getMaxCapacity());
                }
                ensureSQ8Capacity(sq8Size + 1);
                int newOffset = sq8Size;
                System.arraycopy(quantized, 0, sq8Data, newOffset * dim, dim);
                idOffsetIndex.put(id, newOffset);
                payloadStorage.put(newOffset, id, document.getText(), document.getMetadata());
                metadataFilterIndex.indexDocument(newOffset, document.getMetadata());
                sq8Size++;
            }
        } else {
            // 默认 Float32 模式
            if (existingOffset != null) {
                vectorBuffer.updateAt(existingOffset, vector);
                deletedBitSet.unmark(existingOffset);
                payloadStorage.put(existingOffset, id, document.getText(), document.getMetadata());
                metadataFilterIndex.indexDocument(existingOffset, document.getMetadata());
            } else {
                if (getActiveCount() >= definition.getMaxCapacity()) {
                    throw new IllegalStateException("Vector store [" + definition.getStoreName() + "] reached max capacity limit: " + definition.getMaxCapacity());
                }
                int newOffset = vectorBuffer.append(vector);
                idOffsetIndex.put(id, newOffset);
                payloadStorage.put(newOffset, id, document.getText(), document.getMetadata());
                metadataFilterIndex.indexDocument(newOffset, document.getMetadata());
            }
        }
    }

    /** 增量更新 SQ8 全局 min/max 标定范围 */
    private void updateSQ8MinMax(float[] vector) {
        for (float v : vector) {
            if (v < sq8Min) sq8Min = v;
            if (v > sq8Max) sq8Max = v;
        }
    }

    /** SQ8 字节缓冲区自动扩容（1.5 倍策略） */
    private void ensureSQ8Capacity(int minCapacity) {
        if (minCapacity > sq8Capacity) {
            int newCapacity = sq8Capacity + (sq8Capacity >> 1);
            if (newCapacity < minCapacity) newCapacity = minCapacity;
            byte[] newData = new byte[newCapacity * definition.getDimension()];
            System.arraycopy(sq8Data, 0, newData, 0, sq8Size * definition.getDimension());
            sq8Data = newData;
            sq8Capacity = newCapacity;
        }
    }

    /**
     * Flat 检索入口。
     */
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }
        float[] queryVector = request.getQueryVector();
        if (queryVector == null || queryVector.length != definition.getDimension()) {
            throw new IllegalArgumentException("Query vector dimension mismatch for store [" + definition.getStoreName() + "]. Expected: " + definition.getDimension() + ", actual: " + (queryVector != null ? queryVector.length : 0));
        }

        int topK = Math.max(request.getTopK(), 1);
        Float minScore = request.getMinScore();
        String metric = definition.getMetric();
        FilterExpression filter = request.getFilter();

        boolean isEuclidean = "EUCLIDEAN".equalsIgnoreCase(metric) || "L2".equalsIgnoreCase(metric);
        int totalCount = sq8Enabled ? sq8Size : vectorBuffer.getSize();

        boolean enableParallel = properties != null && properties.getSearcher().getParallel().isEnabled()
                && totalCount >= properties.getSearcher().getParallel().getMinVectorCount();

        if (enableParallel) {
            return searchParallel(request, queryVector, topK, minScore, metric, filter, isEuclidean, totalCount);
        } else {
            return searchSequential(request, queryVector, topK, minScore, metric, filter, isEuclidean, totalCount);
        }
    }

    /**
     * 单线程顺序 Flat 检索逻辑。
     */
    private List<VectorSearchResult> searchSequential(VectorSearchRequest request, float[] queryVector, int topK,
                                                       Float minScore, String metric, FilterExpression filter,
                                                       boolean isEuclidean, int totalCount) {
        PriorityQueue<VectorSearchResult> heap = new PriorityQueue<>(topK, isEuclidean ? 
                Comparator.comparingDouble(VectorSearchResult::getScore).reversed() : 
                Comparator.comparingDouble(VectorSearchResult::getScore));

        // 预先使用倒排位图计算匹配 BitSet（零开销前置过滤）
        BitSet matchingBitSet = metadataFilterIndex.evaluate(filter);

        if (!sq8Enabled && vectorBuffer != null) {
            vectorBuffer.acquireReadLock();
        }
        try {
            int dim = definition.getDimension();
            byte[] targetBytes = sq8Enabled ? new byte[dim] : null;

            for (int offset = 0; offset < totalCount; offset++) {
                if (deletedBitSet.isDeleted(offset)) continue;

                // 1. 高速倒排位图前置过滤（零对象开销，单指令按位判断）
                if (matchingBitSet != null && !matchingBitSet.get(offset)) {
                    continue;
                }
                // 2. 未建倒排索引的动态字段降级比对
                if (matchingBitSet == null && filter != null) {
                    Map<String, Object> metadata = payloadStorage.getMetadata(offset);
                    if (!matchesFilter(metadata, filter)) continue;
                }

                float score;
                if (sq8Enabled) {
                    System.arraycopy(sq8Data, offset * dim, targetBytes, 0, dim);
                    score = SQ8Quantizer.calculateCosine(queryVector, targetBytes, sq8Min, sq8Max);
                } else {
                    score = vectorBuffer.calculateScoreZeroCopy(vectorMath, metric, queryVector, offset);
                }

                if (minScore != null) {
                    if (isEuclidean && score > minScore) continue;
                    else if (!isEuclidean && score < minScore) continue;
                }

                String id = payloadStorage.getId(offset);
                String text = payloadStorage.getText(offset);
                Map<String, Object> metadata = payloadStorage.getMetadata(offset);

                VectorSearchResult result = new VectorSearchResult(id, score, text, metadata);
                if (heap.size() < topK) {
                    heap.offer(result);
                } else if (heap.peek() != null) {
                    if (isEuclidean && score < heap.peek().getScore()) {
                        heap.poll();
                        heap.offer(result);
                    } else if (!isEuclidean && score > heap.peek().getScore()) {
                        heap.poll();
                        heap.offer(result);
                    }
                }
            }
        } finally {
            if (!sq8Enabled && vectorBuffer != null) {
                vectorBuffer.releaseReadLock();
            }
        }

        List<VectorSearchResult> results = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            results.add(heap.poll());
        }
        Collections.reverse(results);
        return results;
    }

    /**
     * 多线程并行 Chunk 分段检索逻辑。
     */
    private List<VectorSearchResult> searchParallel(VectorSearchRequest request, float[] queryVector, int topK,
                                                     Float minScore, String metric, FilterExpression filter,
                                                     boolean isEuclidean, int totalCount) {
        int threads = properties.getSearcher().getParallel().getThreads();
        ExecutorService executor = ParallelSearchExecutor.getExecutor(threads);

        int chunkSize = (int) Math.ceil((double) totalCount / threads);
        List<Future<List<VectorSearchResult>>> futures = new ArrayList<>(threads);

        final int dim = definition.getDimension();
        // 预先使用倒排位图计算匹配 BitSet（多线程共享只读 BitSet）
        final BitSet matchingBitSet = metadataFilterIndex.evaluate(filter);

        if (!sq8Enabled && vectorBuffer != null) {
            vectorBuffer.acquireReadLock();
        }
        try {
            for (int i = 0; i < threads; i++) {
                final int startOffset = i * chunkSize;
                final int endOffset = Math.min(startOffset + chunkSize, totalCount);

                if (startOffset >= totalCount) break;

                futures.add(executor.submit(() -> {
                    PriorityQueue<VectorSearchResult> localHeap = new PriorityQueue<>(topK, isEuclidean ?
                            Comparator.comparingDouble(VectorSearchResult::getScore).reversed() :
                            Comparator.comparingDouble(VectorSearchResult::getScore));

                    byte[] localSQ8Bytes = sq8Enabled ? new byte[dim] : null;

                    for (int offset = startOffset; offset < endOffset; offset++) {
                        if (deletedBitSet.isDeleted(offset)) continue;

                        // 1. 位图零开销前置过滤
                        if (matchingBitSet != null && !matchingBitSet.get(offset)) {
                            continue;
                        }
                        // 2. 降级过滤
                        if (matchingBitSet == null && filter != null) {
                            Map<String, Object> metadata = payloadStorage.getMetadata(offset);
                            if (!matchesFilter(metadata, filter)) continue;
                        }

                        float score;
                        if (sq8Enabled) {
                            System.arraycopy(sq8Data, offset * dim, localSQ8Bytes, 0, dim);
                            score = SQ8Quantizer.calculateCosine(queryVector, localSQ8Bytes, sq8Min, sq8Max);
                        } else {
                            score = vectorBuffer.calculateScoreZeroCopy(vectorMath, metric, queryVector, offset);
                        }

                        if (minScore != null) {
                            if (isEuclidean && score > minScore) continue;
                            else if (!isEuclidean && score < minScore) continue;
                        }

                        String id = payloadStorage.getId(offset);
                        String text = payloadStorage.getText(offset);
                        Map<String, Object> metadata = payloadStorage.getMetadata(offset);

                        VectorSearchResult result = new VectorSearchResult(id, score, text, metadata);
                        if (localHeap.size() < topK) {
                            localHeap.offer(result);
                        } else if (localHeap.peek() != null) {
                            if (isEuclidean && score < localHeap.peek().getScore()) {
                                localHeap.poll();
                                localHeap.offer(result);
                            } else if (!isEuclidean && score > localHeap.peek().getScore()) {
                                localHeap.poll();
                                localHeap.offer(result);
                            }
                        }
                    }

                    List<VectorSearchResult> chunkResults = new ArrayList<>(localHeap.size());
                    while (!localHeap.isEmpty()) {
                        chunkResults.add(localHeap.poll());
                    }
                    return chunkResults;
                }));
            }

            // 合并所有线程的局部 TopK 结果
            PriorityQueue<VectorSearchResult> globalHeap = new PriorityQueue<>(topK, isEuclidean ?
                    Comparator.comparingDouble(VectorSearchResult::getScore).reversed() :
                    Comparator.comparingDouble(VectorSearchResult::getScore));

            for (Future<List<VectorSearchResult>> future : futures) {
                List<VectorSearchResult> chunkList = future.get();
                for (VectorSearchResult res : chunkList) {
                    if (globalHeap.size() < topK) {
                        globalHeap.offer(res);
                    } else if (globalHeap.peek() != null) {
                        if (isEuclidean && res.getScore() < globalHeap.peek().getScore()) {
                            globalHeap.poll();
                            globalHeap.offer(res);
                        } else if (!isEuclidean && res.getScore() > globalHeap.peek().getScore()) {
                            globalHeap.poll();
                            globalHeap.offer(res);
                        }
                    }
                }
            }

            List<VectorSearchResult> results = new ArrayList<>(globalHeap.size());
            while (!globalHeap.isEmpty()) {
                results.add(globalHeap.poll());
            }
            Collections.reverse(results);
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Parallel search failed: " + e.getMessage(), e);
        } finally {
            if (!sq8Enabled && vectorBuffer != null) {
                vectorBuffer.releaseReadLock();
            }
        }
    }

    /**
     * 根据 ID 列表逻辑删除向量。
     */
    public synchronized DeleteResult deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new DeleteResult(0);
        }
        int count = 0;
        for (String id : ids) {
            Integer offset = idOffsetIndex.getOffset(id);
            if (offset != null && !deletedBitSet.isDeleted(offset)) {
                deletedBitSet.markDeleted(offset);
                Map<String, Object> metadata = payloadStorage.getMetadata(offset);
                metadataFilterIndex.removeDocument(offset, metadata);
                count++;
            }
        }
        return new DeleteResult(count);
    }

    /**
     * 根据 Filter 条件逻辑删除匹配的所有向量。
     */
    public synchronized DeleteResult deleteByFilter(FilterExpression filter) {
        if (filter == null) {
            return new DeleteResult(0);
        }
        int count = 0;
        int totalCount = sq8Enabled ? sq8Size : vectorBuffer.getSize();
        BitSet matchingBitSet = metadataFilterIndex.evaluate(filter);

        for (int offset = 0; offset < totalCount; offset++) {
            if (deletedBitSet.isDeleted(offset)) {
                continue;
            }
            if (matchingBitSet != null && !matchingBitSet.get(offset)) {
                continue;
            }
            Map<String, Object> metadata = payloadStorage.getMetadata(offset);
            if (matchingBitSet == null && !matchesFilter(metadata, filter)) {
                continue;
            }
            deletedBitSet.markDeleted(offset);
            metadataFilterIndex.removeDocument(offset, metadata);
            count++;
        }
        return new DeleteResult(count);
    }

    /**
     * 获取 Store 当前统计数据。
     */
    public VectorStoreStats getStats() {
        VectorStoreStats stats = new VectorStoreStats();
        stats.setStoreName(definition.getStoreName());
        stats.setDimension(definition.getDimension());
        stats.setDocCount(getActiveCount());
        stats.setMaxCapacity(definition.getMaxCapacity());
        stats.setMetric(definition.getMetric());
        stats.setQuantization(definition.getQuantization());
        return stats;
    }

    /**
     * 获取当前有效（未被逻辑删除）的向量总数。
     */
    public int getActiveCount() {
        return idOffsetIndex.size() - deletedBitSet.getDeletedCount();
    }

    public VectorStoreDefinition getDefinition() {
        return definition;
    }

    /**
     * 校验文档元数据是否匹配 Filter 表达式。
     */
    private boolean matchesFilter(Map<String, Object> metadata, FilterExpression filter) {
        if (filter == null || filter.getField() == null) {
            return true;
        }
        if (metadata == null) {
            return false;
        }
        Object metaValue = metadata.get(filter.getField());
        if (filter.getOperator() == FilterExpression.Operator.EQ) {
            return Objects.equals(metaValue, filter.getValue());
        } else if (filter.getOperator() == FilterExpression.Operator.IN) {
            List<Object> values = filter.getValues();
            return values != null && values.contains(metaValue);
        }
        return true;
    }

    // -- 专门提供给 SnapshotFileStorage 持久化使用的包级访问器 --

    public int getVectorBufferSize() {
        return sq8Enabled ? sq8Size : (vectorBuffer != null ? vectorBuffer.getSize() : 0);
    }

    public boolean isOffsetDeleted(int offset) {
        return deletedBitSet.isDeleted(offset);
    }

    public void copyVectorFromBuffer(int offset, float[] dest) {
        if (sq8Enabled) {
            int dim = definition.getDimension();
            byte[] bytes = new byte[dim];
            System.arraycopy(sq8Data, offset * dim, bytes, 0, dim);
            SQ8Quantizer.dequantize(bytes, sq8Min, sq8Max, dest);
        } else {
            vectorBuffer.copyVectorTo(offset, dest);
        }
    }

    public void copySQ8VectorFromBuffer(int offset, byte[] dest) {
        int dim = definition.getDimension();
        System.arraycopy(sq8Data, offset * dim, dest, 0, dim);
    }

    public DocumentPayload getDocumentPayloadAt(int offset) {
        return payloadStorage.get(offset);
    }

    public float[] getBufferRawData() {
        return vectorBuffer != null ? vectorBuffer.getRawData() : null;
    }

    public byte[] getSQ8RawData() {
        return sq8Data;
    }

    public float getSQ8Min() { return sq8Min; }
    public float getSQ8Max() { return sq8Max; }
    public void setSQ8MinMax(float min, float max) {
        this.sq8Min = min;
        this.sq8Max = max;
    }

    public int getBufferDimension() {
        return definition.getDimension();
    }

    /** 获取 SQ8 量化字节缓冲区实际使用的字节数 */
    public long getSQ8DataSizeBytes() {
        return sq8Enabled ? (long) sq8Size * definition.getDimension() : 0;
    }

    /** 是否启用了 SQ8 量化 */
    public boolean isSQ8Enabled() {
        return sq8Enabled;
    }
}

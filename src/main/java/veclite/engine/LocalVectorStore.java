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
 * 单个向量库（Vector Store）的核心实现类 (V2.3 升级版)。
 * <p>
 * 支持：
 * 1. 向量缓冲区 (Float32 FloatVectorBuffer / In-Heap SQ8 / Off-Heap SQ8 OffHeapSQ8Buffer)
 * 2. 数值化轻量 ID 映射 (IdOffsetIndex -> IntLongIdIndex)
 * 3. 软删除标记位图 (DeletedBitSet)
 * 4. 倒排位图索引中心 (MetadataFilterIndex)
 * 5. 延迟加载/内存 Payload 存储 (CompactPayloadStorage / MMapPayloadStorage)
 */
public class LocalVectorStore {

    private final VectorStoreDefinition definition;
    private final VectorLiteProperties properties;
    private final FloatVectorBuffer vectorBuffer;
    private final IdOffsetIndex idOffsetIndex;
    private final DeletedBitSet deletedBitSet;
    private final MetadataFilterIndex metadataFilterIndex;
    private final PayloadStorage payloadStorage;
    private final VectorMath vectorMath;

    // ---- SQ8 量化与堆外内存字段 ----
    private final boolean sq8Enabled;
    private final boolean offHeapEnabled;
    private final OffHeapSQ8Buffer offHeapSQ8Buffer;
    private volatile byte[] sq8Data;
    private volatile int[] sq8ByteSums;
    private volatile int[] sq8ByteSqSums;
    private int sq8Capacity;
    private int sq8Size;
    private float sq8Min = Float.MAX_VALUE;
    private float sq8Max = -Float.MAX_VALUE;

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

    private static class TopKCandidate {
        final int offset;
        final float score;

        TopKCandidate(int offset, float score) {
            this.offset = offset;
            this.score = score;
        }

        public float getScore() { return score; }
    }

    public LocalVectorStore(VectorStoreDefinition definition) {
        this(definition, null);
    }

    public LocalVectorStore(VectorStoreDefinition definition, VectorLiteProperties properties) {
        this.definition = definition;
        this.properties = properties;
        this.idOffsetIndex = new IdOffsetIndex();
        this.deletedBitSet = new DeletedBitSet();
        this.metadataFilterIndex = new MetadataFilterIndex(definition.getIndexedMetadataFields());
        this.vectorMath = new PureJavaVectorMath();

        boolean useMMap = properties != null && properties.getStorage().getPayload().getMode() == PayloadMode.MMAP;
        if (useMMap) {
            String basePath = properties.getStorage().getSnapshotFile().getBasePath();
            this.payloadStorage = new MMapPayloadStorage(definition.getStoreName(), basePath, 1024);
        } else {
            this.payloadStorage = new CompactPayloadStorage(1024);
        }

        if (definition.getQuantization() == QuantizationType.SQ8) {
            this.sq8Enabled = true;
            this.offHeapEnabled = properties != null && properties.getStorage().getOffHeap().isEnabled();
            this.sq8Capacity = 1024;
            this.sq8ByteSums = new int[this.sq8Capacity];
            this.sq8ByteSqSums = new int[this.sq8Capacity];
            if (this.offHeapEnabled) {
                this.offHeapSQ8Buffer = new OffHeapSQ8Buffer(definition.getDimension(), 1024);
                this.sq8Data = null;
            } else {
                this.offHeapSQ8Buffer = null;
                this.sq8Data = new byte[this.sq8Capacity * definition.getDimension()];
            }
            this.sq8Size = 0;
            this.vectorBuffer = null;
        } else {
            this.sq8Enabled = false;
            this.offHeapEnabled = false;
            this.offHeapSQ8Buffer = null;
            this.sq8ByteSums = null;
            this.sq8ByteSqSums = null;
            this.vectorBuffer = new FloatVectorBuffer(definition.getDimension(), 1024);
        }
    }

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
            updateSQ8MinMax(vector);
            int dim = definition.getDimension();
            byte[] quantized = new byte[dim];
            SQ8Quantizer.quantize(vector, sq8Min, sq8Max, quantized);

            int bSum = 0;
            int bSqSum = 0;
            for (byte b : quantized) {
                bSum += b;
                bSqSum += b * b;
            }

            if (existingOffset != null) {
                if (offHeapEnabled && offHeapSQ8Buffer != null) {
                    offHeapSQ8Buffer.updateAt(existingOffset, quantized);
                } else {
                    System.arraycopy(quantized, 0, sq8Data, existingOffset * dim, dim);
                }
                if (existingOffset < sq8ByteSums.length) {
                    sq8ByteSums[existingOffset] = bSum;
                    sq8ByteSqSums[existingOffset] = bSqSum;
                }
                deletedBitSet.unmark(existingOffset);
                payloadStorage.put(existingOffset, id, document.getText(), document.getMetadata());
                metadataFilterIndex.indexDocument(existingOffset, document.getMetadata());
            } else {
                if (getActiveCount() >= definition.getMaxCapacity()) {
                    throw new IllegalStateException("Vector store [" + definition.getStoreName() + "] reached max capacity limit: " + definition.getMaxCapacity());
                }
                int newOffset;
                if (offHeapEnabled && offHeapSQ8Buffer != null) {
                    newOffset = offHeapSQ8Buffer.append(quantized);
                    sq8Size = offHeapSQ8Buffer.getSize();
                } else {
                    ensureSQ8Capacity(sq8Size + 1);
                    newOffset = sq8Size;
                    System.arraycopy(quantized, 0, sq8Data, newOffset * dim, dim);
                    sq8Size++;
                }
                ensureNormsCapacity(newOffset + 1);
                sq8ByteSums[newOffset] = bSum;
                sq8ByteSqSums[newOffset] = bSqSum;
                idOffsetIndex.put(id, newOffset);
                payloadStorage.put(newOffset, id, document.getText(), document.getMetadata());
                metadataFilterIndex.indexDocument(newOffset, document.getMetadata());
            }
        } else {
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

    private void updateSQ8MinMax(float[] vector) {
        for (float v : vector) {
            if (v < sq8Min) sq8Min = v;
            if (v > sq8Max) sq8Max = v;
        }
    }

    private void ensureSQ8Capacity(int minCapacity) {
        if (!offHeapEnabled && minCapacity > sq8Capacity) {
            int newCapacity = sq8Capacity + (sq8Capacity >> 1);
            if (newCapacity < minCapacity) newCapacity = minCapacity;
            byte[] newData = new byte[newCapacity * definition.getDimension()];
            System.arraycopy(sq8Data, 0, newData, 0, sq8Size * definition.getDimension());
            sq8Data = newData;
            sq8Capacity = newCapacity;
        }
    }

    private void ensureNormsCapacity(int minCapacity) {
        if (sq8ByteSums == null || sq8ByteSqSums == null) {
            sq8ByteSums = new int[Math.max(minCapacity, 1024)];
            sq8ByteSqSums = new int[Math.max(minCapacity, 1024)];
        } else if (minCapacity > sq8ByteSums.length) {
            int newCap = sq8ByteSums.length + (sq8ByteSums.length >> 1);
            if (newCap < minCapacity) newCap = minCapacity;
            int[] newSums = new int[newCap];
            int[] newSqSums = new int[newCap];
            System.arraycopy(sq8ByteSums, 0, newSums, 0, sq8ByteSums.length);
            System.arraycopy(sq8ByteSqSums, 0, newSqSums, 0, sq8ByteSqSums.length);
            sq8ByteSums = newSums;
            sq8ByteSqSums = newSqSums;
        }
    }

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
        int totalCount = sq8Enabled ? (offHeapEnabled ? offHeapSQ8Buffer.getSize() : sq8Size) : vectorBuffer.getSize();

        boolean enableParallel = properties != null && properties.getSearcher().getParallel().isEnabled()
                && totalCount >= properties.getSearcher().getParallel().getMinVectorCount();

        if (enableParallel) {
            return searchParallel(request, queryVector, topK, minScore, metric, filter, isEuclidean, totalCount);
        } else {
            return searchSequential(request, queryVector, topK, minScore, metric, filter, isEuclidean, totalCount);
        }
    }

    private List<VectorSearchResult> searchSequential(VectorSearchRequest request, float[] queryVector, int topK,
                                                       Float minScore, String metric, FilterExpression filter,
                                                       boolean isEuclidean, int totalCount) {
        PriorityQueue<TopKCandidate> heap = new PriorityQueue<>(topK, isEuclidean ?
                Comparator.comparingDouble(TopKCandidate::getScore).reversed() :
                Comparator.comparingDouble(TopKCandidate::getScore));

        BitSet matchingBitSet = metadataFilterIndex.evaluate(filter);

        boolean usePrecomp = sq8Enabled && (properties == null || properties.getSearcher().getPrecomputation().isEnabled());
        SQ8Quantizer.SQ8QueryPrecomputation precomp = usePrecomp ? SQ8Quantizer.precompute(queryVector, sq8Min, sq8Max) : null;

        if (sq8Enabled && offHeapEnabled && offHeapSQ8Buffer != null) {
            offHeapSQ8Buffer.acquireReadLock();
        } else if (!sq8Enabled && vectorBuffer != null) {
            vectorBuffer.acquireReadLock();
        }
        try {
            int dim = definition.getDimension();
            byte[] targetBytes = sq8Enabled ? new byte[dim] : null;

            for (int offset = 0; offset < totalCount; offset++) {
                if (deletedBitSet.isDeleted(offset)) continue;

                if (matchingBitSet != null && !matchingBitSet.get(offset)) {
                    continue;
                }
                if (matchingBitSet == null && filter != null) {
                    Map<String, Object> metadata = payloadStorage.getMetadata(offset);
                    if (!matchesFilter(metadata, filter)) continue;
                }

                float score;
                if (sq8Enabled) {
                    if (usePrecomp) {
                        int bSum = (sq8ByteSums != null && offset < sq8ByteSums.length) ? sq8ByteSums[offset] : 0;
                        int bSqSum = (sq8ByteSqSums != null && offset < sq8ByteSqSums.length) ? sq8ByteSqSums[offset] : 0;
                        float targetNormSq = precomp.d_c1_sq + precomp.c1_c2_2 * bSum + precomp.c2_sq * bSqSum;
                        if (offHeapEnabled && offHeapSQ8Buffer != null) {
                            offHeapSQ8Buffer.getDirectBuffer().get(offset * dim, targetBytes, 0, dim);
                            score = SQ8Quantizer.calculateScorePrecomputed(precomp, targetBytes, 0, targetNormSq, metric);
                        } else {
                            score = SQ8Quantizer.calculateScorePrecomputed(precomp, sq8Data, offset * dim, targetNormSq, metric);
                        }
                    } else {
                        if (offHeapEnabled && offHeapSQ8Buffer != null) {
                            offHeapSQ8Buffer.copyVectorTo(offset, targetBytes);
                        } else {
                            System.arraycopy(sq8Data, offset * dim, targetBytes, 0, dim);
                        }
                        score = SQ8Quantizer.calculateCosine(queryVector, targetBytes, sq8Min, sq8Max);
                    }
                } else {
                    score = vectorBuffer.calculateScoreZeroCopy(vectorMath, metric, queryVector, offset);
                }

                if (minScore != null) {
                    if (isEuclidean && score > minScore) continue;
                    else if (!isEuclidean && score < minScore) continue;
                }

                if (heap.size() < topK) {
                    heap.offer(new TopKCandidate(offset, score));
                } else if (heap.peek() != null) {
                    float currentPeekScore = heap.peek().getScore();
                    if (isEuclidean ? (score < currentPeekScore) : (score > currentPeekScore)) {
                        heap.poll();
                        heap.offer(new TopKCandidate(offset, score));
                    }
                }
            }
        } finally {
            if (sq8Enabled && offHeapEnabled && offHeapSQ8Buffer != null) {
                offHeapSQ8Buffer.releaseReadLock();
            } else if (!sq8Enabled && vectorBuffer != null) {
                vectorBuffer.releaseReadLock();
            }
        }

        List<VectorSearchResult> results = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            TopKCandidate cand = heap.poll();
            DocumentPayload payload = payloadStorage.get(cand.offset);
            String id = payload != null ? payload.getId() : idOffsetIndex.getId(cand.offset);
            String text = payload != null ? payload.getText() : null;
            Map<String, Object> metadata = payload != null ? payload.getMetadata() : null;
            results.add(new VectorSearchResult(id, cand.score, text, metadata));
        }
        Collections.reverse(results);
        return results;
    }

    private List<VectorSearchResult> searchParallel(VectorSearchRequest request, float[] queryVector, int topK,
                                                     Float minScore, String metric, FilterExpression filter,
                                                     boolean isEuclidean, int totalCount) {
        int threads = properties.getSearcher().getParallel().getThreads();
        ExecutorService executor = ParallelSearchExecutor.getExecutor(threads);

        int chunkSize = (int) Math.ceil((double) totalCount / threads);
        List<Future<List<TopKCandidate>>> futures = new ArrayList<>(threads);

        final int dim = definition.getDimension();
        final BitSet matchingBitSet = metadataFilterIndex.evaluate(filter);

        boolean usePrecomp = sq8Enabled && (properties == null || properties.getSearcher().getPrecomputation().isEnabled());
        SQ8Quantizer.SQ8QueryPrecomputation precomp = usePrecomp ? SQ8Quantizer.precompute(queryVector, sq8Min, sq8Max) : null;

        if (sq8Enabled && offHeapEnabled && offHeapSQ8Buffer != null) {
            offHeapSQ8Buffer.acquireReadLock();
        } else if (!sq8Enabled && vectorBuffer != null) {
            vectorBuffer.acquireReadLock();
        }
        try {
            for (int i = 0; i < threads; i++) {
                final int startOffset = i * chunkSize;
                final int endOffset = Math.min(startOffset + chunkSize, totalCount);

                if (startOffset >= totalCount) break;

                futures.add(executor.submit(() -> {
                    PriorityQueue<TopKCandidate> localHeap = new PriorityQueue<>(topK, isEuclidean ?
                            Comparator.comparingDouble(TopKCandidate::getScore).reversed() :
                            Comparator.comparingDouble(TopKCandidate::getScore));

                    byte[] localSQ8Bytes = sq8Enabled ? new byte[dim] : null;

                    for (int offset = startOffset; offset < endOffset; offset++) {
                        if (deletedBitSet.isDeleted(offset)) continue;

                        if (matchingBitSet != null && !matchingBitSet.get(offset)) {
                            continue;
                        }
                        if (matchingBitSet == null && filter != null) {
                            Map<String, Object> metadata = payloadStorage.getMetadata(offset);
                            if (!matchesFilter(metadata, filter)) continue;
                        }

                        float score;
                        if (sq8Enabled) {
                            if (usePrecomp) {
                                int bSum = (sq8ByteSums != null && offset < sq8ByteSums.length) ? sq8ByteSums[offset] : 0;
                                int bSqSum = (sq8ByteSqSums != null && offset < sq8ByteSqSums.length) ? sq8ByteSqSums[offset] : 0;
                                float targetNormSq = precomp.d_c1_sq + precomp.c1_c2_2 * bSum + precomp.c2_sq * bSqSum;
                                if (offHeapEnabled && offHeapSQ8Buffer != null) {
                                    offHeapSQ8Buffer.getDirectBuffer().get(offset * dim, localSQ8Bytes, 0, dim);
                                    score = SQ8Quantizer.calculateScorePrecomputed(precomp, localSQ8Bytes, 0, targetNormSq, metric);
                                } else {
                                    score = SQ8Quantizer.calculateScorePrecomputed(precomp, sq8Data, offset * dim, targetNormSq, metric);
                                }
                            } else {
                                if (offHeapEnabled && offHeapSQ8Buffer != null) {
                                    offHeapSQ8Buffer.copyVectorTo(offset, localSQ8Bytes);
                                } else {
                                    System.arraycopy(sq8Data, offset * dim, localSQ8Bytes, 0, dim);
                                }
                                score = SQ8Quantizer.calculateCosine(queryVector, localSQ8Bytes, sq8Min, sq8Max);
                            }
                        } else {
                            score = vectorBuffer.calculateScoreZeroCopy(vectorMath, metric, queryVector, offset);
                        }

                        if (minScore != null) {
                            if (isEuclidean && score > minScore) continue;
                            else if (!isEuclidean && score < minScore) continue;
                        }

                        if (localHeap.size() < topK) {
                            localHeap.offer(new TopKCandidate(offset, score));
                        } else if (localHeap.peek() != null) {
                            float currentPeekScore = localHeap.peek().getScore();
                            if (isEuclidean ? (score < currentPeekScore) : (score > currentPeekScore)) {
                                localHeap.poll();
                                localHeap.offer(new TopKCandidate(offset, score));
                            }
                        }
                    }

                    List<TopKCandidate> chunkResults = new ArrayList<>(localHeap.size());
                    while (!localHeap.isEmpty()) {
                        chunkResults.add(localHeap.poll());
                    }
                    return chunkResults;
                }));
            }

            PriorityQueue<TopKCandidate> globalHeap = new PriorityQueue<>(topK, isEuclidean ?
                    Comparator.comparingDouble(TopKCandidate::getScore).reversed() :
                    Comparator.comparingDouble(TopKCandidate::getScore));

            for (Future<List<TopKCandidate>> future : futures) {
                List<TopKCandidate> chunkList = future.get();
                for (TopKCandidate res : chunkList) {
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
                TopKCandidate cand = globalHeap.poll();
                DocumentPayload payload = payloadStorage.get(cand.offset);
                String id = payload != null ? payload.getId() : idOffsetIndex.getId(cand.offset);
                String text = payload != null ? payload.getText() : null;
                Map<String, Object> metadata = payload != null ? payload.getMetadata() : null;
                results.add(new VectorSearchResult(id, cand.score, text, metadata));
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

    public synchronized DeleteResult deleteByFilter(FilterExpression filter) {
        if (filter == null) {
            return new DeleteResult(0);
        }
        int count = 0;
        int totalCount = sq8Enabled ? (offHeapEnabled ? offHeapSQ8Buffer.getSize() : sq8Size) : vectorBuffer.getSize();
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
     * Returns document payloads in insertion-offset order. Vectors are intentionally omitted
     * because a management list should not transfer potentially large vector arrays.
     */
    public synchronized VectorDocumentPage listDocuments(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int total = getActiveCount();
        int start = safePage * safeSize;
        if (start >= total) {
            return new VectorDocumentPage(Collections.emptyList(), safePage, safeSize, total);
        }

        List<VectorDocument> documents = new ArrayList<>(Math.min(safeSize, total - start));
        int seen = 0;
        int totalCount = getVectorBufferSize();
        for (int offset = 0; offset < totalCount && documents.size() < safeSize; offset++) {
            if (deletedBitSet.isDeleted(offset)) {
                continue;
            }
            if (seen++ < start) {
                continue;
            }
            DocumentPayload payload = payloadStorage.get(offset);
            String id = payload != null ? payload.getId() : idOffsetIndex.getId(offset);
            if (id != null) {
                documents.add(new VectorDocument(id,
                        null,
                        payload != null ? payload.getText() : null,
                        payload != null ? payload.getMetadata() : null));
            }
        }
        return new VectorDocumentPage(documents, safePage, safeSize, total);
    }

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

    public int getActiveCount() {
        return idOffsetIndex.size() - deletedBitSet.getDeletedCount();
    }

    public VectorStoreDefinition getDefinition() {
        return definition;
    }

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
        return sq8Enabled ? (offHeapEnabled ? offHeapSQ8Buffer.getSize() : sq8Size) : (vectorBuffer != null ? vectorBuffer.getSize() : 0);
    }

    public boolean isOffsetDeleted(int offset) {
        return deletedBitSet.isDeleted(offset);
    }

    public void copyVectorFromBuffer(int offset, float[] dest) {
        if (sq8Enabled) {
            int dim = definition.getDimension();
            byte[] bytes = new byte[dim];
            copySQ8VectorFromBuffer(offset, bytes);
            SQ8Quantizer.dequantize(bytes, sq8Min, sq8Max, dest);
        } else {
            vectorBuffer.copyVectorTo(offset, dest);
        }
    }

    public void copySQ8VectorFromBuffer(int offset, byte[] dest) {
        if (offHeapEnabled && offHeapSQ8Buffer != null) {
            offHeapSQ8Buffer.copyVectorTo(offset, dest);
        } else if (sq8Data != null) {
            int dim = definition.getDimension();
            System.arraycopy(sq8Data, offset * dim, dest, 0, dim);
        }
    }

    public DocumentPayload getDocumentPayloadAt(int offset) {
        return payloadStorage.get(offset);
    }

    public float[] getBufferRawData() {
        return vectorBuffer != null ? vectorBuffer.getRawData() : null;
    }

    public byte[] getSQ8RawData() {
        if (offHeapEnabled && offHeapSQ8Buffer != null) {
            byte[] data = new byte[offHeapSQ8Buffer.getSize() * definition.getDimension()];
            offHeapSQ8Buffer.copyAllTo(data);
            return data;
        }
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

    public long getSQ8DataSizeBytes() {
        return sq8Enabled ? (long) getVectorBufferSize() * definition.getDimension() : 0;
    }

    public boolean isSQ8Enabled() {
        return sq8Enabled;
    }

    public boolean isOffHeapEnabled() {
        return sq8Enabled && offHeapEnabled;
    }

    public void close() {
        if (payloadStorage != null) {
            try {
                payloadStorage.close();
            } catch (Exception ignored) {
            }
        }
    }
}

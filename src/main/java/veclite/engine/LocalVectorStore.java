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
 * 单个向量库（Vector Store）的核心实现类 (V2.4 升级版)。
 * <p>
 * 支持：
 * 1. 向量缓冲区 (Float32 FloatVectorBuffer / In-Heap SQ8 / Off-Heap SQ8 OffHeapSQ8Buffer)
 * 2. 数值化轻量 ID 映射 (IdOffsetIndex -> IntLongIdIndex)
 * 3. 软删除标记位图 (DeletedBitSet)
 * 4. 倒排位图索引中心 (MetadataFilterIndex)
 * 5. 延迟加载/内存 Payload 存储 (CompactPayloadStorage / MMapPayloadStorage)
 * 6. Per-Dimension 校准冻结式 SQ8 量化（前 N 条向量校准统计后冻结参数，杜绝量化漂移）
 */
public class LocalVectorStore {

    /**
     * SQ8 量化参数校准样本数：前 N 条向量以 Float32 暂存用于统计逐维度 min/max，
     * 达到该数量后一次性量化并冻结量化参数（约占用 N × dim × 4 字节临时内存）。
     */
    private static final int SQ8_CALIBRATION_SIZE = 1024;

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

    /** 堆内 SQ8 平铺向量数据（off-heap 关闭时使用） */
    private volatile byte[] sq8Data;
    private int sq8Capacity;
    private int sq8Size;

    /** 逐维度量化参数（冻结后不可变） */
    private volatile float[] sq8MinPerDim;
    private volatile float[] sq8ScalePerDim;

    /** 量化参数是否已冻结；volatile 保证搜索线程立即可见 */
    private volatile boolean sq8Frozen;

    /** 已存向量的预计算 L2 范数缓存（下标与 Offset 对齐） */
    private float[] sq8Norms;

    /** 校准阶段暂存的原始 Float32 向量（冻结后释放置空） */
    private volatile float[] calibrationVectors;
    private int calibrationCount;

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
            if (this.offHeapEnabled) {
                this.offHeapSQ8Buffer = new OffHeapSQ8Buffer(definition.getDimension(), 1024);
                this.sq8Data = null;
            } else {
                this.offHeapSQ8Buffer = null;
                this.sq8Capacity = 1024;
                this.sq8Data = new byte[this.sq8Capacity * definition.getDimension()];
            }
            this.sq8Size = 0;
            this.sq8Frozen = false;
            this.sq8MinPerDim = null;
            this.sq8ScalePerDim = null;
            this.sq8Norms = new float[Math.max(getFreezeTarget(), 16)];
            this.calibrationVectors = new float[getFreezeTarget() * definition.getDimension()];
            this.calibrationCount = 0;
            this.vectorBuffer = null;
        } else {
            this.sq8Enabled = false;
            this.offHeapEnabled = false;
            this.offHeapSQ8Buffer = null;
            this.sq8Frozen = false;
            this.sq8MinPerDim = null;
            this.sq8ScalePerDim = null;
            this.sq8Norms = null;
            this.calibrationVectors = null;
            this.calibrationCount = 0;
            this.vectorBuffer = new FloatVectorBuffer(definition.getDimension(), 1024);
        }
    }

    /**
     * 重置 Store 全部内存状态（reload 重载快照前调用），回到初始空 Store。
     * 防止 reload 时磁盘快照之外的旧文档、倒排索引与软删除标记残留。
     */
    public synchronized void reset() {
        idOffsetIndex.clear();
        deletedBitSet.clear();
        metadataFilterIndex.clear();
        payloadStorage.clear();
        if (!sq8Enabled) {
            if (vectorBuffer != null) {
                vectorBuffer.clear();
            }
        } else {
            sq8Frozen = false;
            sq8MinPerDim = null;
            sq8ScalePerDim = null;
            sq8Size = 0;
            calibrationVectors = new float[getFreezeTarget() * definition.getDimension()];
            calibrationCount = 0;
            if (sq8Norms != null) {
                Arrays.fill(sq8Norms, 0.0f);
            }
            if (offHeapSQ8Buffer != null) {
                offHeapSQ8Buffer.clear();
            }
        }
    }

    /**
     * 冻结阈值：校准样本数与最大容量取小者（小容量 Store 在写满时立即冻结）。
     */
    private int getFreezeTarget() {
        int maxCapacity = definition.getMaxCapacity();
        if (maxCapacity <= 0) {
            return SQ8_CALIBRATION_SIZE;
        }
        return Math.min(SQ8_CALIBRATION_SIZE, maxCapacity);
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
            if (existingOffset != null) {
                int dim = definition.getDimension();
                if (!sq8Frozen) {
                    // 校准阶段：原地覆盖暂存区的原始向量
                    System.arraycopy(vector, 0, calibrationVectors, existingOffset * dim, dim);
                } else {
                    byte[] quantized = new byte[dim];
                    SQ8Quantizer.quantize(vector, sq8MinPerDim, sq8ScalePerDim, quantized);
                    writeQuantizedAt(existingOffset, quantized);
                    sq8Norms[existingOffset] = quantizedNorm(quantized);
                }
                deletedBitSet.unmark(existingOffset);
                // 先按旧 Metadata 清除倒排位图，再写入新值，防止旧字段值残留导致过滤结果错误
                Map<String, Object> oldMetadata = payloadStorage.getMetadata(existingOffset);
                metadataFilterIndex.removeDocument(existingOffset, oldMetadata);
                metadataFilterIndex.indexDocument(existingOffset, document.getMetadata());
                payloadStorage.put(existingOffset, id, document.getText(), document.getMetadata());
            } else {
                if (getActiveCount() >= definition.getMaxCapacity()) {
                    throw new IllegalStateException("Vector store [" + definition.getStoreName() + "] reached max capacity limit: " + definition.getMaxCapacity());
                }
                int newOffset;
                if (!sq8Frozen) {
                    // 校准阶段：追加至暂存区，达到阈值后统一量化并冻结参数
                    newOffset = calibrationCount++;
                    System.arraycopy(vector, 0, calibrationVectors, newOffset * definition.getDimension(), definition.getDimension());
                    ensureNormsCapacity(newOffset + 1);
                    sq8Norms[newOffset] = 0.0f;
                    if (calibrationCount >= getFreezeTarget()) {
                        freezeSQ8();
                    }
                } else {
                    int dim = definition.getDimension();
                    byte[] quantized = new byte[dim];
                    SQ8Quantizer.quantize(vector, sq8MinPerDim, sq8ScalePerDim, quantized);
                    newOffset = appendQuantized(quantized);
                    ensureNormsCapacity(newOffset + 1);
                    sq8Norms[newOffset] = quantizedNorm(quantized);
                }
                idOffsetIndex.put(id, newOffset);
                payloadStorage.put(newOffset, id, document.getText(), document.getMetadata());
                metadataFilterIndex.indexDocument(newOffset, document.getMetadata());
            }
        } else {
            if (existingOffset != null) {
                vectorBuffer.updateAt(existingOffset, vector);
                deletedBitSet.unmark(existingOffset);
                // 先按旧 Metadata 清除倒排位图，再写入新值，防止旧字段值残留导致过滤结果错误
                Map<String, Object> oldMetadata = payloadStorage.getMetadata(existingOffset);
                metadataFilterIndex.removeDocument(existingOffset, oldMetadata);
                metadataFilterIndex.indexDocument(existingOffset, document.getMetadata());
                payloadStorage.put(existingOffset, id, document.getText(), document.getMetadata());
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

    /**
     * 统计校准暂存区内所有向量的逐维度 min/max，生成量化参数并全量量化，
     * 随后释放暂存区并冻结参数。必须在持有 Store 写锁（synchronized upsert）时调用。
     */
    private void freezeSQ8() {
        int dim = definition.getDimension();
        int n = calibrationCount;

        float[] minP = new float[dim];
        float[] maxP = new float[dim];
        Arrays.fill(minP, Float.MAX_VALUE);
        Arrays.fill(maxP, -Float.MAX_VALUE);
        for (int r = 0; r < n; r++) {
            int base = r * dim;
            for (int d = 0; d < dim; d++) {
                float v = calibrationVectors[base + d];
                if (v < minP[d]) minP[d] = v;
                if (v > maxP[d]) maxP[d] = v;
            }
        }
        float[] scaleP = new float[dim];
        for (int d = 0; d < dim; d++) {
            float range = maxP[d] - minP[d];
            if (range < 1e-7f) range = 1e-7f;
            scaleP[d] = range / 255.0f;
        }

        // 参数先就绪，再批量量化（搜索线程在 frozen 置位前仍走暂存区 Float32 精确扫描）
        this.sq8MinPerDim = minP;
        this.sq8ScalePerDim = scaleP;

        float[] row = new float[dim];
        byte[] quantized = new byte[dim];
        for (int r = 0; r < n; r++) {
            int base = r * dim;
            System.arraycopy(calibrationVectors, base, row, 0, dim);
            SQ8Quantizer.quantize(row, minP, scaleP, quantized);
            int newOffset = appendQuantized(quantized);
            ensureNormsCapacity(newOffset + 1);
            sq8Norms[newOffset] = quantizedNorm(quantized);
        }

        this.calibrationVectors = null;
        this.sq8Frozen = true;
    }

    /**
     * 从量化字节（经反量化后的存储表示）计算 L2 范数。
     * 范数缓存必须与打分时使用的量化表示一致，否则发生 clamp 的向量会因范数失真导致得分错误。
     */
    private float quantizedNorm(byte[] quantized) {
        int dim = definition.getDimension();
        float[] restored = new float[dim];
        SQ8Quantizer.dequantize(quantized, sq8MinPerDim, sq8ScalePerDim, restored);
        return SQ8Quantizer.l2Norm(restored);
    }

    private void writeQuantizedAt(int offset, byte[] quantized) {
        int dim = definition.getDimension();
        if (offHeapEnabled && offHeapSQ8Buffer != null) {
            offHeapSQ8Buffer.updateAt(offset, quantized);
        } else {
            System.arraycopy(quantized, 0, sq8Data, offset * dim, dim);
        }
    }

    private int appendQuantized(byte[] quantized) {
        int dim = definition.getDimension();
        int newOffset;
        if (offHeapEnabled && offHeapSQ8Buffer != null) {
            newOffset = offHeapSQ8Buffer.append(quantized);
        } else {
            ensureSQ8Capacity(sq8Size + 1);
            System.arraycopy(quantized, 0, sq8Data, sq8Size * dim, dim);
            newOffset = sq8Size;
        }
        sq8Size++;
        return newOffset;
    }

    private void ensureNormsCapacity(int minCapacity) {
        if (sq8Norms == null || sq8Norms.length < minCapacity) {
            int oldLen = sq8Norms != null ? sq8Norms.length : 16;
            int newLen = oldLen + (oldLen >> 1);
            if (newLen < minCapacity) newLen = minCapacity;
            sq8Norms = Arrays.copyOf(sq8Norms != null ? sq8Norms : new float[16], newLen);
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
        boolean isDotProduct = "DOT_PRODUCT".equalsIgnoreCase(metric) || "IP".equalsIgnoreCase(metric);

        // 三种扫描模式：Float32 缓冲区 / SQ8 量化缓冲区 / SQ8 校准期 Float32 暂存区。
        // 所有可变状态在入口处一次性捕获为局部快照引用，
        // 防止写线程在校准冻结瞬间置空暂存区导致检索线程 NPE。
        boolean sq8Scan = false;
        boolean warmupScan = false;
        int totalCount;
        float[] calibSnapshot = null;
        if (!sq8Enabled) {
            totalCount = vectorBuffer.getSize();
        } else if (sq8Frozen) {
            sq8Scan = true;
            totalCount = sq8Size;
        } else {
            calibSnapshot = calibrationVectors;
            if (calibSnapshot == null) {
                // 冻结恰好发生在两次读取之间，退化为量化扫描
                sq8Scan = true;
                totalCount = sq8Size;
            } else {
                warmupScan = true;
                totalCount = calibrationCount;
            }
        }

        // 查询向量范数整次搜索仅计算一次
        float queryNormSq = 0.0f;
        float queryNormInv = 0.0f;
        if (sq8Scan) {
            for (float v : queryVector) {
                queryNormSq += v * v;
            }
            queryNormInv = queryNormSq > 0.0f ? 1.0f / (float) Math.sqrt(queryNormSq) : 0.0f;
        }

        boolean enableParallel = properties != null && properties.getSearcher().getParallel().isEnabled()
                && totalCount >= properties.getSearcher().getParallel().getMinVectorCount();

        if (enableParallel) {
            return searchParallel(queryVector, topK, minScore, metric, filter, isEuclidean, isDotProduct,
                    totalCount, sq8Scan, warmupScan, queryNormSq, queryNormInv, calibSnapshot);
        }

        if (!sq8Enabled && vectorBuffer != null) {
            vectorBuffer.acquireReadLock();
        }
        try {
            List<TopKCandidate> candidates = doSearch(queryVector, topK, minScore, metric, filter, isEuclidean, isDotProduct,
                    0, totalCount, sq8Scan, warmupScan, queryNormSq, queryNormInv, calibSnapshot, null);
            return buildResults(candidates);
        } finally {
            if (!sq8Enabled && vectorBuffer != null) {
                vectorBuffer.releaseReadLock();
            }
        }
    }

    private List<VectorSearchResult> searchParallel(float[] queryVector, int topK,
                                                    Float minScore, String metric, FilterExpression filter,
                                                    boolean isEuclidean, boolean isDotProduct, int totalCount,
                                                    boolean sq8Scan, boolean warmupScan,
                                                    float queryNormSq, float queryNormInv,
                                                    float[] calibSnapshot) {
        int threads = properties.getSearcher().getParallel().getThreads();
        ExecutorService executor = ParallelSearchExecutor.getExecutor(threads);

        int chunkSize = (int) Math.ceil((double) totalCount / threads);
        List<Future<List<TopKCandidate>>> futures = new ArrayList<>(threads);

        final int dim = definition.getDimension();
        final BitSet matchingBitSet = metadataFilterIndex.evaluate(filter);

        if (!sq8Enabled && vectorBuffer != null) {
            vectorBuffer.acquireReadLock();
        }
        try {
            for (int i = 0; i < threads; i++) {
                final int startOffset = i * chunkSize;
                final int endOffset = Math.min(startOffset + chunkSize, totalCount);

                if (startOffset >= totalCount) break;

                futures.add(executor.submit(() ->
                        doSearch(queryVector, topK, minScore, metric, filter, isEuclidean, isDotProduct,
                                startOffset, endOffset, sq8Scan, warmupScan, queryNormSq, queryNormInv,
                                calibSnapshot, matchingBitSet)));
            }

            PriorityQueue<TopKCandidate> globalHeap = newPriorityQueue(topK, isEuclidean);

            for (Future<List<TopKCandidate>> future : futures) {
                List<TopKCandidate> chunkList = future.get();
                for (TopKCandidate res : chunkList) {
                    offerCandidate(globalHeap, res, topK, isEuclidean);
                }
            }

            return buildResults(pollAll(globalHeap));
        } catch (Exception e) {
            throw new RuntimeException("Parallel search failed: " + e.getMessage(), e);
        } finally {
            if (!sq8Enabled && vectorBuffer != null) {
                vectorBuffer.releaseReadLock();
            }
        }
    }

    /**
     * 在 [fromOffset, toOffset) 区间内扫描打分并返回候选列表（按 poll 顺序，即从差到好排列）。
     * fromOffset=0 且 toOffset=totalCount 时等价于顺序全量扫描。
     */
    private List<TopKCandidate> doSearch(float[] queryVector, int topK,
                                         Float minScore, String metric, FilterExpression filter,
                                         boolean isEuclidean, boolean isDotProduct,
                                         int fromOffset, int toOffset,
                                         boolean sq8Scan, boolean warmupScan,
                                         float queryNormSq, float queryNormInv,
                                         float[] calibSnapshot,
                                         BitSet precomputedMatchingBitSet) {
        PriorityQueue<TopKCandidate> heap = newPriorityQueue(topK, isEuclidean);

        BitSet matchingBitSet = precomputedMatchingBitSet != null
                ? precomputedMatchingBitSet : metadataFilterIndex.evaluate(filter);

        final int dim = definition.getDimension();
        final float[] minPerDim = sq8Scan ? sq8MinPerDim : null;
        final float[] scalePerDim = sq8Scan ? sq8ScalePerDim : null;
        final float[] norms = sq8Scan ? sq8Norms : null;
        final byte[] sq8HeapData = (sq8Scan && !offHeapEnabled) ? sq8Data : null;
        final byte[] sq8Bytes = sq8Scan && offHeapEnabled ? new byte[dim] : null;

        for (int offset = fromOffset; offset < toOffset; offset++) {
            if (deletedBitSet.isDeleted(offset)) continue;

            if (matchingBitSet != null && !matchingBitSet.get(offset)) {
                continue;
            }
            if (matchingBitSet == null && filter != null) {
                Map<String, Object> metadata = payloadStorage.getMetadata(offset);
                if (!matchesFilter(metadata, filter)) continue;
            }

            float score;
            if (warmupScan) {
                // 校准阶段：直接在 Float32 暂存区快照上做精确计算
                score = vectorMath.calculate(metric, queryVector, calibSnapshot, offset * dim, dim);
            } else if (sq8Scan) {
                byte[] srcArr;
                int srcOff;
                if (sq8Bytes != null) {
                    offHeapSQ8Buffer.copyVectorTo(offset, sq8Bytes);
                    srcArr = sq8Bytes;
                    srcOff = 0;
                } else {
                    srcArr = sq8HeapData;
                    srcOff = offset * dim;
                }
                float norm = norms[offset];
                if (isEuclidean) {
                    score = SQ8Quantizer.calculateEuclideanWithNorms(queryVector, srcArr, srcOff,
                            minPerDim, scalePerDim, queryNormSq, norm * norm);
                } else if (isDotProduct) {
                    score = SQ8Quantizer.calculateDotProduct(queryVector, srcArr, srcOff,
                            minPerDim, scalePerDim);
                } else {
                    float normInv = norm > 0.0f ? 1.0f / norm : 0.0f;
                    score = SQ8Quantizer.calculateCosineWithNorms(queryVector, srcArr, srcOff,
                            minPerDim, scalePerDim, queryNormInv, normInv);
                }
            } else {
                score = vectorBuffer.calculateScoreZeroCopy(vectorMath, metric, queryVector, offset);
            }

            if (minScore != null) {
                if (isEuclidean && score > minScore) continue;
                else if (!isEuclidean && score < minScore) continue;
            }

            offerCandidate(heap, new TopKCandidate(offset, score), topK, isEuclidean);
        }

        return pollAll(heap);
    }

    private PriorityQueue<TopKCandidate> newPriorityQueue(int topK, boolean isEuclidean) {
        return new PriorityQueue<>(topK, isEuclidean ?
                Comparator.comparingDouble(TopKCandidate::getScore).reversed() :
                Comparator.comparingDouble(TopKCandidate::getScore));
    }

    private void offerCandidate(PriorityQueue<TopKCandidate> heap, TopKCandidate candidate, int topK, boolean isEuclidean) {
        if (heap.size() < topK) {
            heap.offer(candidate);
        } else if (heap.peek() != null) {
            float peekScore = heap.peek().getScore();
            boolean better = isEuclidean ? candidate.score < peekScore : candidate.score > peekScore;
            if (better) {
                heap.poll();
                heap.offer(candidate);
            }
        }
    }

    private List<TopKCandidate> pollAll(PriorityQueue<TopKCandidate> heap) {
        List<TopKCandidate> results = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            results.add(heap.poll());
        }
        return results;
    }

    private List<VectorSearchResult> buildResults(List<TopKCandidate> candidates) {
        List<VectorSearchResult> results = new ArrayList<>(candidates.size());
        for (int i = candidates.size() - 1; i >= 0; i--) {
            TopKCandidate cand = candidates.get(i);
            DocumentPayload payload = payloadStorage.get(cand.offset);
            String id = payload != null ? payload.getId() : idOffsetIndex.getId(cand.offset);
            String text = payload != null ? payload.getText() : null;
            Map<String, Object> metadata = payload != null ? payload.getMetadata() : null;
            results.add(new VectorSearchResult(id, cand.score, text, metadata));
        }
        return results;
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

    /**
     * 求值元数据过滤条件，返回当前有效且命中的文档 ID（不做任何删除）。
     * 供文档型持久化的写透删除（deleteByFilter 场景先取 ID 再删真相源）使用，
     * 匹配语义与 {@link #deleteByFilter(FilterExpression)} 完全一致。
     */
    public synchronized List<String> findIdsByFilter(FilterExpression filter) {
        if (filter == null) {
            return new ArrayList<>();
        }
        List<String> matchedIds = new ArrayList<>();
        int totalCount = getVectorBufferSize();
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
            DocumentPayload payload = payloadStorage.get(offset);
            if (payload != null) {
                matchedIds.add(payload.getId());
            }
        }
        return matchedIds;
    }

    public synchronized DeleteResult deleteByFilter(FilterExpression filter) {
        if (filter == null) {
            return new DeleteResult(0);
        }
        return deleteByIds(findIdsByFilter(filter));
    }

    public VectorStoreStats getStats() {
        VectorStoreStats stats = new VectorStoreStats();
        stats.setStoreName(definition.getStoreName());
        stats.setDimension(definition.getDimension());
        stats.setDocCount(getActiveCount());
        stats.setMaxCapacity(definition.getMaxCapacity());
        stats.setMetric(definition.getMetric());
        stats.setQuantization(definition.getQuantization());
        stats.setEmbeddingModel(definition.getEmbeddingModel());
        return stats;
    }

    /**
     * 按 ID 精确查询单个文档（含文本与元数据），用于管理与调试界面。
     *
     * @param includeVector 是否回填原始向量；为 false 时返回的文档 vector 为 null
     * @return 文档不存在或已删除时返回 null
     */
    public synchronized VectorDocument getDocument(String id, boolean includeVector) {
        if (id == null) {
            return null;
        }
        Integer offset = idOffsetIndex.getOffset(id);
        if (offset == null || isOffsetDeleted(offset)) {
            return null;
        }
        DocumentPayload payload = getDocumentPayloadAt(offset);
        if (payload == null) {
            return null;
        }
        float[] vector = null;
        if (includeVector) {
            vector = new float[definition.getDimension()];
            copyVectorFromBuffer(offset, vector);
        }
        return new VectorDocument(payload.getId(), vector, payload.getText(), payload.getMetadata());
    }

    public synchronized List<VectorDocument> listDocuments(int page, int size, boolean includeVector) {
        if (page < 1) page = 1;
        if (size <= 0) size = 20;
        int skip = (page - 1) * size;
        int bufferSize = getVectorBufferSize();
        int dim = definition.getDimension();
        List<VectorDocument> result = new ArrayList<>();
        int passed = 0;

        for (int i = 0; i < bufferSize; i++) {
            if (!isOffsetDeleted(i)) {
                DocumentPayload payload = getDocumentPayloadAt(i);
                if (payload != null) {
                    if (passed >= skip && result.size() < size) {
                        float[] vec = null;
                        if (includeVector) {
                            vec = new float[dim];
                            copyVectorFromBuffer(i, vec);
                        }
                        VectorDocument doc = new VectorDocument(payload.getId(), vec, payload.getText(), payload.getMetadata());
                        result.add(doc);
                    }
                    passed++;
                }
            }
        }
        return result;
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
        } else if (filter.getOperator() == FilterExpression.Operator.GT
                || filter.getOperator() == FilterExpression.Operator.LT) {
            return compareNumeric(metaValue, filter.getValue())
                    == (filter.getOperator() == FilterExpression.Operator.GT
                            ? 1 : -1);
        }
        return true;
    }

    /**
     * 把元数据值与过滤值都按数值比较。无法转成数字的视为不可比较，返回 -2（外层比较结果不会等于 ±1 → 不命中）。
     * 这样前端把"123"传过来，元数据是 Integer/Long/Double 都能比较；
     * 字符串"abc"或布尔值不会被错误地按数字排。
     */
    private static int compareNumeric(Object metaValue, Object filterValue) {
        if (metaValue == null || filterValue == null) {
            return -2;
        }
        Double a = toDouble(metaValue);
        Double b = toDouble(filterValue);
        if (a == null || b == null) {
            return -2;
        }
        return Double.compare(a, b);
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble((String) o); }
            catch (NumberFormatException e) { return null; }
        }
        if (o instanceof Boolean) return ((Boolean) o) ? 1.0 : 0.0;
        return null;
    }

    // -- 专门提供给 SnapshotFileStorage 持久化使用的包级访问器 --

    public int getVectorBufferSize() {
        if (!sq8Enabled) {
            return vectorBuffer != null ? vectorBuffer.getSize() : 0;
        }
        return sq8Frozen ? sq8Size : calibrationCount;
    }

    /**
     * v2.4 兼容：payload 与 vector 共用同一 buffer 的同号位置，
     * 因此其 size 与 {@link #getVectorBufferSize()} 相等。
     */
    public int getPayloadSize() {
        return getVectorBufferSize();
    }

    /**
     * v2.4 兼容：id 索引按 buffer 顺序写入，
     * 因此其 size 与 {@link #getVectorBufferSize()} 相等。
     */
    public int getIdIndexSize() {
        return getVectorBufferSize();
    }

    public boolean isOffsetDeleted(int offset) {
        return deletedBitSet.isDeleted(offset);
    }

    public void copyVectorFromBuffer(int offset, float[] dest) {
        int dim = definition.getDimension();
        if (!sq8Enabled) {
            vectorBuffer.copyVectorTo(offset, dest);
        } else if (!sq8Frozen) {
            System.arraycopy(calibrationVectors, offset * dim, dest, 0, dim);
        } else {
            byte[] bytes = new byte[dim];
            copySQ8VectorFromBuffer(offset, bytes);
            SQ8Quantizer.dequantize(bytes, sq8MinPerDim, sq8ScalePerDim, dest);
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
            byte[] data = new byte[sq8Size * definition.getDimension()];
            offHeapSQ8Buffer.copyAllTo(data);
            return data;
        }
        return sq8Data;
    }

    public float[] getSQ8MinPerDim() {
        return sq8MinPerDim;
    }

    public float[] getSQ8ScalePerDim() {
        return sq8ScalePerDim;
    }

    public boolean isSQ8Frozen() {
        return sq8Frozen;
    }

    public boolean isSQ8Enabled() {
        return sq8Enabled;
    }

    public boolean isOffHeapEnabled() {
        return sq8Enabled && offHeapEnabled;
    }

    /**
     * 快照恢复专用：直接注入已冻结的逐维度量化参数（跳过本进程的校准流程）。
     */
    public void restoreFrozenParams(float[] minPerDim, float[] scalePerDim) {
        if (!sq8Enabled) {
            throw new IllegalStateException("Store [" + definition.getStoreName() + "] is not in SQ8 mode");
        }
        if (minPerDim == null || scalePerDim == null || minPerDim.length != definition.getDimension()
                || scalePerDim.length != definition.getDimension()) {
            throw new IllegalArgumentException("Invalid per-dimension quantization params");
        }
        this.sq8MinPerDim = minPerDim;
        this.sq8ScalePerDim = scalePerDim;
        this.calibrationVectors = null;
        this.calibrationCount = 0;
        this.sq8Size = 0;
        this.sq8Frozen = true;
    }

    /**
     * 快照恢复专用：将磁盘上的原始 SQ8 量化字节直接入 Buffer，
     * 不经过"反量化→重新量化"往返，避免多次刷盘/恢复后的精度累积衰减。
     * 必须先调用 {@link #restoreFrozenParams(float[], float[])}。
     */
    public synchronized void restoreDocumentWithSQ8(VectorDocument document, byte[] rawQuantized) {
        if (!sq8Frozen) {
            throw new IllegalStateException("Must call restoreFrozenParams before restoring SQ8 documents");
        }
        int dim = definition.getDimension();
        if (rawQuantized == null || rawQuantized.length != dim) {
            throw new IllegalArgumentException("Raw SQ8 bytes dimension mismatch");
        }
        int newOffset = appendQuantized(rawQuantized);

        ensureNormsCapacity(newOffset + 1);
        float[] restored = new float[dim];
        SQ8Quantizer.dequantize(rawQuantized, sq8MinPerDim, sq8ScalePerDim, restored);
        sq8Norms[newOffset] = SQ8Quantizer.l2Norm(restored);

        idOffsetIndex.put(document.getId(), newOffset);
        payloadStorage.put(newOffset, document.getId(), document.getText(), document.getMetadata());
        metadataFilterIndex.indexDocument(newOffset, document.getMetadata());
    }

    public long getSQ8DataSizeBytes() {
        return sq8Enabled ? (long) getVectorBufferSize() * definition.getDimension() : 0;
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

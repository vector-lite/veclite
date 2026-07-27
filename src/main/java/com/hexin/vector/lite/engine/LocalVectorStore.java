package com.hexin.vector.lite.engine;

import com.hexin.vector.lite.api.VectorStoreDefinition;
import com.hexin.vector.lite.math.PureJavaVectorMath;
import com.hexin.vector.lite.math.VectorMath;
import com.hexin.vector.lite.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LocalVectorStore {

    private final VectorStoreDefinition definition;
    private final FloatVectorBuffer vectorBuffer;
    private final IdOffsetIndex idOffsetIndex;
    private final DeletedBitSet deletedBitSet;
    private final Map<Integer, DocumentPayload> documentPayloads;
    private final VectorMath vectorMath;

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
        this.definition = definition;
        this.vectorBuffer = new FloatVectorBuffer(definition.getDimension(), 1024);
        this.idOffsetIndex = new IdOffsetIndex();
        this.deletedBitSet = new DeletedBitSet();
        this.documentPayloads = new ConcurrentHashMap<>();
        this.vectorMath = new PureJavaVectorMath();
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
        if (existingOffset != null) {
            vectorBuffer.updateAt(existingOffset, vector);
            deletedBitSet.unmark(existingOffset);
            documentPayloads.put(existingOffset, new DocumentPayload(id, document.getText(), document.getMetadata()));
        } else {
            if (getActiveCount() >= definition.getMaxCapacity()) {
                throw new IllegalStateException("Vector store [" + definition.getStoreName() + "] reached max capacity limit: " + definition.getMaxCapacity());
            }
            int newOffset = vectorBuffer.append(vector);
            idOffsetIndex.put(id, newOffset);
            documentPayloads.put(newOffset, new DocumentPayload(id, document.getText(), document.getMetadata()));
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

        PriorityQueue<VectorSearchResult> minHeap = new PriorityQueue<>(topK, Comparator.comparingDouble(VectorSearchResult::getScore));

        int totalCount = vectorBuffer.getSize();
        float[] candidateVector = new float[definition.getDimension()];

        for (int offset = 0; offset < totalCount; offset++) {
            if (deletedBitSet.isDeleted(offset)) {
                continue;
            }
            DocumentPayload payload = documentPayloads.get(offset);
            if (payload == null) {
                continue;
            }

            if (filter != null && !matchesFilter(payload.getMetadata(), filter)) {
                continue;
            }

            vectorBuffer.copyVectorTo(offset, candidateVector);
            float score = vectorMath.calculate(metric, queryVector, candidateVector);

            if (minScore != null && score < minScore) {
                continue;
            }

            VectorSearchResult result = new VectorSearchResult(payload.getId(), score, payload.getText(), payload.getMetadata());
            if (minHeap.size() < topK) {
                minHeap.offer(result);
            } else if (minHeap.peek() != null && score > minHeap.peek().getScore()) {
                minHeap.poll();
                minHeap.offer(result);
            }
        }

        List<VectorSearchResult> results = new ArrayList<>(minHeap.size());
        while (!minHeap.isEmpty()) {
            results.add(minHeap.poll());
        }
        Collections.reverse(results);
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
        int totalCount = vectorBuffer.getSize();
        for (int offset = 0; offset < totalCount; offset++) {
            if (deletedBitSet.isDeleted(offset)) {
                continue;
            }
            DocumentPayload payload = documentPayloads.get(offset);
            if (payload != null && matchesFilter(payload.getMetadata(), filter)) {
                deletedBitSet.markDeleted(offset);
                count++;
            }
        }
        return new DeleteResult(count);
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

    // -- Package-level accessors for SnapshotFileStorage (avoids reflection) --

    public int getVectorBufferSize() {
        return vectorBuffer.getSize();
    }

    public boolean isOffsetDeleted(int offset) {
        return deletedBitSet.isDeleted(offset);
    }

    public void copyVectorFromBuffer(int offset, float[] dest) {
        vectorBuffer.copyVectorTo(offset, dest);
    }

    public DocumentPayload getDocumentPayloadAt(int offset) {
        return documentPayloads.get(offset);
    }

    public float[] getBufferRawData() {
        return vectorBuffer.getRawData();
    }

    public int getBufferDimension() {
        return vectorBuffer.getDimension();
    }
}

package veclite.persistence.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.types.Binary;
import veclite.config.VectorLiteProperties;
import veclite.model.QuantizationType;
import veclite.api.VectorStoreMetadata;
import veclite.persistence.VectorDocumentEntity;
import veclite.persistence.VectorDocumentRepository;
import veclite.persistence.VectorStorageFormat;
import veclite.persistence.StoreNameValidator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link VectorDocumentRepository} 的 MongoDB 适配器（v2.5 单一真相源持久化的默认实现）。
 * <p>
 * 集合结构见 design/v2.5/mongodb_persistence_design.md §2：
 * <ul>
 *   <li>每个 Store 使用独立文档集合，{@code doc_id} 唯一索引，向量以 BinData 存储
 *       （禁止 BSON double 数组——每元素 8 字节 double + 类型标记会带来近 3 倍膨胀）；</li>
 *   <li>元数据集合：{@code store_name} 唯一索引，1 库 1 文档。</li>
 * </ul>
 */
public class MongoVectorDocumentRepository implements VectorDocumentRepository {

    private static final String FIELD_STORE_NAME = "store_name";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_UPDATED_AT = "updated_at";
    private static final String FIELD_DELETED = "deleted";

    private static final String META_DIMENSION = "dimension";
    private static final String META_METRIC = "metric";
    private static final String META_MAX_CAPACITY = "max_capacity";
    private static final String META_EMBEDDING_MODEL = "embedding_model";
    private static final String META_EMBEDDING_MODEL_VERSION = "embedding_model_version";
    private static final String META_QUANTIZATION = "quantization";
    private static final String META_INDEXED_FIELDS = "indexed_metadata_fields";
    private static final String META_ACTIVE_COUNT = "active_count";
    private static final String META_SQ8_MIN = "sq8_min_per_dim";
    private static final String META_SQ8_SCALE = "sq8_scale_per_dim";
    private static final String META_CREATED_AT = "created_at";
    private static final String META_SYNC_WATERMARK = "sync_watermark";

    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final String metaCollectionName;
    private final int scanBatchSize;
    /** 已执行过 DDL（建索引）的集合缓存：ensureStore 在每次批量写都会调用，避免重复往返 */
    private final Set<String> ensuredCollections = ConcurrentHashMap.newKeySet();

    public MongoVectorDocumentRepository(VectorLiteProperties properties) {
        VectorLiteProperties.MongoConfig config = properties.getStorage().getMongodb();
        this.mongoClient = MongoClients.create(new ConnectionString(config.getUri()));
        this.database = mongoClient.getDatabase(config.getDatabase());
        this.metaCollectionName = config.getMetaCollection();
        this.scanBatchSize = config.getScanBatchSize() > 0 ? config.getScanBatchSize() : 1000;
        ensureSchema();
    }

    MongoVectorDocumentRepository(MongoClient mongoClient, MongoDatabase database,
                                  String documentCollectionName, String metaCollectionName, int scanBatchSize) {
        this.mongoClient = mongoClient;
        this.database = database;
        this.metaCollectionName = metaCollectionName;
        this.scanBatchSize = scanBatchSize > 0 ? scanBatchSize : 1000;
        ensureSchema();
    }

    @Override
    public void ensureSchema() {
        storeMeta().createIndex(new Document(FIELD_STORE_NAME, 1));
    }

    @Override
    public void ensureStore(String storeName) {
        StoreNameValidator.validate(storeName);
        if (ensuredCollections.add(storeName)) {
            MongoCollection<Document> collection = documents(storeName);
            collection.createIndex(new Document(FIELD_DOC_ID, 1), new com.mongodb.client.model.IndexOptions().unique(true));
            collection.createIndex(new Document(FIELD_UPDATED_AT, 1));
        }
    }

    @Override
    public void dropStore(String storeName) {
        StoreNameValidator.validate(storeName);
        documents(storeName).drop();
        ensuredCollections.remove(storeName);
        deleteStoreMetadata(storeName);
    }

    @Override
    public void upsertBatch(String storeName, List<VectorDocumentEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        ensureStore(storeName);
        MongoCollection<Document> collection = documents(storeName);
        List<ReplaceOneModel<Document>> operations = new ArrayList<>(entities.size());
        for (VectorDocumentEntity entity : entities) {
            operations.add(new ReplaceOneModel<>(
                    Filters.eq(FIELD_DOC_ID, entity.getDocId()),
                    toDocument(entity),
                    new ReplaceOptions().upsert(true)));
        }
        // ordered=false：批量导入时单条冲突不阻塞整批，最大化吞吐
        collection.bulkWrite(operations, new BulkWriteOptions().ordered(false));
    }

    @Override
    public long deleteByIds(String storeName, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return 0;
        }
        // 软删除：保留 tombstone 行（deleted=true），供其他节点经增量扫描感知删除
        UpdateResult result = documents(storeName).updateMany(
                Filters.in(FIELD_DOC_ID, documentIds),
                new Document("$set", new Document(FIELD_DELETED, true).append(FIELD_UPDATED_AT, new Date())));
        return result.getModifiedCount();
    }

    @Override
    public long deleteAll(String storeName) {
        return documents(storeName).deleteMany(new Document()).getDeletedCount();
    }

    @Override
    public Iterator<VectorDocumentEntity> scan(String storeName) {
        return documents(storeName)
                .find(Filters.ne(FIELD_DELETED, true))
                .batchSize(scanBatchSize)
                .map(doc -> toEntity(doc, VectorStorageFormat.FLOAT32))
                .iterator();
    }

    @Override
    public Iterator<VectorDocumentEntity> scanUpdatedSince(String storeName, Instant watermark) {
        return documents(storeName)
                .find(Filters.gt(FIELD_UPDATED_AT, Date.from(watermark)))
                .batchSize(scanBatchSize)
                .map(doc -> toEntity(doc, VectorStorageFormat.FLOAT32))
                .iterator();
    }

    @Override
    public long countUpdatedSince(String storeName, Instant watermark) {
        return documents(storeName).countDocuments(Filters.gt(FIELD_UPDATED_AT, Date.from(watermark)));
    }

    @Override
    public long purgeSoftDeletedBefore(String storeName, Instant cutoff) {
        DeleteResult result = documents(storeName).deleteMany(
                Filters.and(Filters.eq(FIELD_DELETED, true), Filters.lt(FIELD_UPDATED_AT, Date.from(cutoff))));
        return result.getDeletedCount();
    }

    @Override
    public List<String> listDocumentIds(String storeName) {
        List<String> ids = new ArrayList<>();
        try (MongoCursor<Document> cursor = documents(storeName)
                .find(Filters.ne(FIELD_DELETED, true))
                .projection(Projections.include(FIELD_DOC_ID))
                .batchSize(scanBatchSize)
                .iterator()) {
            while (cursor.hasNext()) {
                ids.add(cursor.next().getString(FIELD_DOC_ID));
            }
        }
        return ids;
    }

    @Override
    public long count(String storeName) {
        return documents(storeName).countDocuments();
    }

    /**
     * 以 $set 部分更新保存元数据（upsert）。与旧版整体 replace 的差别：
     * created_at 改为仅在插入时写入；同步水位入参为 null 时保留库中现值，
     * 避免 createStore 等仅登记定义的调用方把增量水位抹掉。
     */
    @Override
    public void saveStoreMetadata(VectorStoreMetadata metadata) {
        Date now = new Date();
        Document set = new Document();
        set.append(META_DIMENSION, metadata.getDimension());
        set.append(META_METRIC, metadata.getMetric());
        set.append(META_MAX_CAPACITY, metadata.getMaxCapacity());
        set.append(META_EMBEDDING_MODEL, metadata.getEmbeddingModel());
        set.append(META_EMBEDDING_MODEL_VERSION, metadata.getEmbeddingModelVersion());
        set.append(META_QUANTIZATION, metadata.getQuantization() != null ? metadata.getQuantization().name() : QuantizationType.NONE.name());
        set.append(META_INDEXED_FIELDS, metadata.getIndexedMetadataFields());
        set.append(META_ACTIVE_COUNT, metadata.getActiveCount());
        set.append(META_SQ8_MIN, metadata.getSq8MinPerDim() != null ? VectorDocumentEntity.encodeVector(metadata.getSq8MinPerDim()) : null);
        set.append(META_SQ8_SCALE, metadata.getSq8ScalePerDim() != null ? VectorDocumentEntity.encodeVector(metadata.getSq8ScalePerDim()) : null);
        set.append(FIELD_UPDATED_AT, now);
        if (metadata.getSyncWatermark() != null) {
            set.append(META_SYNC_WATERMARK, Date.from(metadata.getSyncWatermark()));
        }
        Document setOnInsert = new Document(META_CREATED_AT,
                metadata.getCreatedAt() != null ? Date.from(metadata.getCreatedAt()) : now);
        storeMeta().updateOne(
                Filters.eq(FIELD_STORE_NAME, metadata.getStoreName()),
                new Document("$set", set).append("$setOnInsert", setOnInsert),
                new UpdateOptions().upsert(true));
    }

    @Override
    public Optional<VectorStoreMetadata> findStoreMetadata(String storeName) {
        Document doc = storeMeta().find(Filters.eq(FIELD_STORE_NAME, storeName)).first();
        return doc != null ? Optional.of(toMetadata(doc)) : Optional.empty();
    }

    @Override
    public List<VectorStoreMetadata> listStoreMetadata() {
        List<VectorStoreMetadata> result = new ArrayList<>();
        try (MongoCursor<Document> cursor = storeMeta().find().iterator()) {
            while (cursor.hasNext()) {
                result.add(toMetadata(cursor.next()));
            }
        }
        return result;
    }

    @Override
    public void deleteStoreMetadata(String storeName) {
        storeMeta().deleteOne(Filters.eq(FIELD_STORE_NAME, storeName));
    }

    @Override
    public void close() {
        mongoClient.close();
    }

    private MongoCollection<Document> documents(String storeName) {
        StoreNameValidator.validate(storeName);
        return database.getCollection(storeName);
    }

    private MongoCollection<Document> storeMeta() {
        return database.getCollection(metaCollectionName);
    }

    private Document toDocument(VectorDocumentEntity entity) {
        Document doc = new Document();
        doc.append(FIELD_DOC_ID, entity.getDocId());
        doc.append(FIELD_TEXT, entity.getText());
        doc.append(FIELD_METADATA, entity.getMetadata() != null ? new Document(entity.getMetadata()) : null);
        if (entity.getFormat() == VectorStorageFormat.SQ8) {
            doc.append(FIELD_VECTOR, entity.getSq8Vector());
        } else {
            doc.append(FIELD_VECTOR, VectorDocumentEntity.encodeVector(entity.getVector()));
        }
        doc.append(FIELD_UPDATED_AT, new Date());
        // upsert 整体替换文档，顺带清除 tombstone（被删除的 docId 重新写入即复活）
        doc.append(FIELD_DELETED, entity.isDeleted());
        return doc;
    }

    private VectorDocumentEntity toEntity(Document doc, VectorStorageFormat format) {
        VectorDocumentEntity entity = new VectorDocumentEntity();
        entity.setDocId(doc.getString(FIELD_DOC_ID));
        entity.setText(doc.getString(FIELD_TEXT));
        Document metadata = doc.get(FIELD_METADATA, Document.class);
        entity.setMetadata(metadata != null ? new HashMap<>(metadata) : null);
        entity.setFormat(format);
        byte[] vector = asBytes(doc.get(FIELD_VECTOR));
        if (vector != null) {
            if (entity.getFormat() == VectorStorageFormat.SQ8) {
                entity.setSq8Vector(vector);
            } else {
                entity.setVector(VectorDocumentEntity.decodeVector(vector));
            }
        }
        Date updatedAt = doc.getDate(FIELD_UPDATED_AT);
        entity.setUpdatedAt(updatedAt != null ? updatedAt.toInstant() : null);
        entity.setDeleted(doc.getBoolean(FIELD_DELETED, false));
        return entity;
    }

    /**
     * BinData 字段读回时驱动返回 {@link Binary} 而非原生 byte[]，统一解包。
     */
    private byte[] asBytes(Object value) {
        if (value instanceof Binary binary) {
            return binary.getData();
        }
        return value instanceof byte[] bytes ? bytes : null;
    }

    private VectorStoreMetadata toMetadata(Document doc) {
        VectorStoreMetadata metadata = new VectorStoreMetadata();
        metadata.setStoreName(doc.getString(FIELD_STORE_NAME));
        metadata.setDimension(doc.getInteger(META_DIMENSION, 0));
        metadata.setMetric(doc.getString(META_METRIC));
        metadata.setMaxCapacity(doc.getInteger(META_MAX_CAPACITY, 100000));
        metadata.setEmbeddingModel(doc.getString(META_EMBEDDING_MODEL));
        metadata.setEmbeddingModelVersion(doc.getString(META_EMBEDDING_MODEL_VERSION));
        String quantization = doc.getString(META_QUANTIZATION);
        try {
            metadata.setQuantization(quantization != null ? QuantizationType.valueOf(quantization) : QuantizationType.NONE);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown quantization type in store metadata: " + quantization, e);
        }
        List<String> indexedFields = doc.getList(META_INDEXED_FIELDS, String.class);
        metadata.setIndexedMetadataFields(indexedFields != null ? indexedFields : new ArrayList<>());
        metadata.setActiveCount(doc.getInteger(META_ACTIVE_COUNT, 0));
        byte[] sq8Min = asBytes(doc.get(META_SQ8_MIN));
        byte[] sq8Scale = asBytes(doc.get(META_SQ8_SCALE));
        if (sq8Min != null && sq8Scale != null) {
            metadata.setSq8MinPerDim(VectorDocumentEntity.decodeVector(sq8Min));
            metadata.setSq8ScalePerDim(VectorDocumentEntity.decodeVector(sq8Scale));
        }
        Date createdAt = doc.getDate(META_CREATED_AT);
        metadata.setCreatedAt(createdAt != null ? createdAt.toInstant() : null);
        Date updatedAt = doc.getDate(FIELD_UPDATED_AT);
        metadata.setUpdatedAt(updatedAt != null ? updatedAt.toInstant() : null);
        Date syncWatermark = doc.getDate(META_SYNC_WATERMARK);
        metadata.setSyncWatermark(syncWatermark != null ? syncWatermark.toInstant() : null);
        return metadata;
    }
}

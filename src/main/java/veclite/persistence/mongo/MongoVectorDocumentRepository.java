package veclite.persistence.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.types.Binary;
import veclite.config.VectorLiteProperties;
import veclite.model.QuantizationType;
import veclite.model.StorageType;
import veclite.api.VectorStoreMetadata;
import veclite.persistence.VectorDocumentEntity;
import veclite.persistence.VectorDocumentRepository;
import veclite.persistence.VectorStorageFormat;
import veclite.persistence.StoreNameValidator;
import veclite.persistence.StorePersistenceHandle;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private static final String META_DIMENSION = "dimension";
    private static final String META_METRIC = "metric";
    private static final String META_MAX_CAPACITY = "max_capacity";
    private static final String META_EMBEDDING_MODEL = "embedding_model";
    private static final String META_EMBEDDING_MODEL_VERSION = "embedding_model_version";
    private static final String META_QUANTIZATION = "quantization";
    private static final String META_INDEXED_FIELDS = "indexed_metadata_fields";
    private static final String META_PERSISTENCE_MODE = "persistence_mode";
    private static final String META_ACTIVE_COUNT = "active_count";
    private static final String META_SQ8_MIN = "sq8_min_per_dim";
    private static final String META_SQ8_SCALE = "sq8_scale_per_dim";
    private static final String META_CREATED_AT = "created_at";

    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final String metaCollectionName;
    private final int scanBatchSize;

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
    public StorePersistenceHandle ensureStore(String storeName) {
        StoreNameValidator.validate(storeName);
        MongoCollection<Document> collection = documents(storeName);
        collection.createIndex(new Document(FIELD_DOC_ID, 1), new com.mongodb.client.model.IndexOptions().unique(true));
        collection.createIndex(new Document(FIELD_UPDATED_AT, 1));
        return new StorePersistenceHandle(storeName, storeName);
    }

    @Override
    public StorePersistenceHandle handle(String storeName) {
        StoreNameValidator.validate(storeName);
        return new StorePersistenceHandle(storeName, storeName);
    }

    @Override
    public void dropStore(String storeName) {
        StoreNameValidator.validate(storeName);
        documents(storeName).drop();
        deleteStoreMetadata(storeName);
    }

    @Override
    public void upsertBatch(String storeName, List<VectorDocumentEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        MongoCollection<Document> collection = ensureStore(storeName) != null ? documents(storeName) : null;
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
        DeleteResult result = documents(storeName).deleteMany(Filters.in(FIELD_DOC_ID, documentIds));
        return result.getDeletedCount();
    }

    @Override
    public long deleteAll(String storeName) {
        return documents(storeName).deleteMany(new Document()).getDeletedCount();
    }

    @Override
    public Iterator<VectorDocumentEntity> scan(String storeName) {
        return documents(storeName)
                .find()
                .batchSize(scanBatchSize)
                .map(doc -> toEntity(doc, VectorStorageFormat.FLOAT32))
                .iterator();
    }

    @Override
    public List<String> listDocumentIds(String storeName) {
        List<String> ids = new ArrayList<>();
        try (MongoCursor<Document> cursor = documents(storeName)
                .find()
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

    @Override
    public void saveStoreMetadata(VectorStoreMetadata metadata) {
        Document doc = toMetaDocument(metadata);
        storeMeta().replaceOne(
                Filters.eq(FIELD_STORE_NAME, metadata.getStoreName()),
                doc,
                new ReplaceOptions().upsert(true));
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

    private VectorStorageFormat parseFormat(String name) {
        if (name == null) {
            return VectorDocumentEntity.DEFAULT_FORMAT;
        }
        try {
            return VectorStorageFormat.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown vector storage format in document persistence: " + name, e);
        }
    }

    private Document toMetaDocument(VectorStoreMetadata metadata) {
        Document doc = new Document();
        doc.append(FIELD_STORE_NAME, metadata.getStoreName());
        doc.append(META_DIMENSION, metadata.getDimension());
        doc.append(META_METRIC, metadata.getMetric());
        doc.append(META_MAX_CAPACITY, metadata.getMaxCapacity());
        doc.append(META_EMBEDDING_MODEL, metadata.getEmbeddingModel());
        doc.append(META_EMBEDDING_MODEL_VERSION, metadata.getEmbeddingModelVersion());
        doc.append(META_QUANTIZATION, metadata.getQuantization() != null ? metadata.getQuantization().name() : QuantizationType.NONE.name());
        doc.append(META_INDEXED_FIELDS, metadata.getIndexedMetadataFields());
        doc.append(META_PERSISTENCE_MODE, metadata.getPersistenceMode() != null ? metadata.getPersistenceMode().name() : StorageType.MONGODB.name());
        doc.append(META_ACTIVE_COUNT, metadata.getActiveCount());
        doc.append(META_SQ8_MIN, metadata.getSq8MinPerDim() != null ? VectorDocumentEntity.encodeVector(metadata.getSq8MinPerDim()) : null);
        doc.append(META_SQ8_SCALE, metadata.getSq8ScalePerDim() != null ? VectorDocumentEntity.encodeVector(metadata.getSq8ScalePerDim()) : null);
        Date now = new Date();
        doc.append(META_CREATED_AT, metadata.getCreatedAt() != null ? Date.from(metadata.getCreatedAt()) : now);
        doc.append(FIELD_UPDATED_AT, now);
        return doc;
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
        String mode = doc.getString(META_PERSISTENCE_MODE);
        try {
            metadata.setPersistenceMode(mode != null ? StorageType.valueOf(mode) : StorageType.MONGODB);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown persistence mode in store metadata: " + mode, e);
        }
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
        return metadata;
    }
}

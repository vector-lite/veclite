package veclite.persistence.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingModelRef;
import veclite.embedding.EmbeddingModelStore;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * {@link EmbeddingModelStore} 的 MongoDB 实现：托管模型配置存储于
 * {@code veclite_embedding_model} 集合，唯一键为（name, version）复合索引。
 */
public class MongoEmbeddingModelStore implements EmbeddingModelStore {

    private static final String FIELD_NAME = "name";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_URL = "url";
    private static final String FIELD_PROVIDER = "provider";
    private static final String FIELD_API_KEY = "api_key";
    private static final String FIELD_DIMENSION = "dimension";
    private static final String FIELD_TIMEOUT = "timeout_millis";
    private static final String FIELD_BATCH_SIZE = "batch_size";
    private static final String FIELD_UPDATED_AT = "updated_at";
    private static final String FIELD_IS_DEFAULT = "is_default";

    private final MongoClient mongoClient;
    private final MongoCollection<Document> collection;

    public MongoEmbeddingModelStore(VectorLiteProperties properties) {
        VectorLiteProperties.MongoConfig config = properties.getStorage().getMongodb();
        this.mongoClient = MongoClients.create(new ConnectionString(config.getUri()));
        MongoDatabase database = mongoClient.getDatabase(config.getDatabase());
        this.collection = database.getCollection(config.getEmbeddingModelCollection());
        migrateIndexes();
    }

    MongoEmbeddingModelStore(MongoCollection<Document> collection, MongoClient mongoClient) {
        this.mongoClient = mongoClient;
        this.collection = collection;
    }

    /**
     * 唯一索引迁移：唯一键经历两次演进——
     * v1 单字段 {@code name_1}（非唯一）、v2 单字段 {@code name_1}（唯一）、
     * v3 复合 {@code name_1_version_1}（唯一）。启动时检测旧索引定义，
     * 不符合当前定义的先删除再重建。索引操作失败不阻断启动。
     */
    private void migrateIndexes() {
        try {
            boolean hasLegacyNameIndex = false;
            boolean hasCurrentCompoundIndex = false;
            for (Document index : collection.listIndexes()) {
                String name = index.getString("name");
                if ("name_1".equals(name)) {
                    hasLegacyNameIndex = true;
                } else if ("name_1_version_1".equals(name)) {
                    hasCurrentCompoundIndex = Boolean.TRUE.equals(index.getBoolean("unique"));
                }
            }
            if (hasLegacyNameIndex) {
                collection.dropIndex("name_1");
            }
            if (!hasCurrentCompoundIndex) {
                collection.createIndex(
                        new Document(FIELD_NAME, 1).append(FIELD_VERSION, 1),
                        new IndexOptions().unique(true).name("name_1_version_1"));
            }
            collection.createIndex(new Document(FIELD_IS_DEFAULT, 1),
                    new IndexOptions().unique(true).partialFilterExpression(Filters.eq(FIELD_IS_DEFAULT, true)).name("uq_embedding_default"));
        } catch (Exception ignored) {
            // 索引迁移失败只影响防重保护强度（upsert 仍按 name+version 过滤生效），不阻断应用启动
        }
    }

    @Override
    public List<VectorLiteProperties.ModelConfig> loadAll() {
        List<VectorLiteProperties.ModelConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
            config.setName(doc.getString(FIELD_NAME));
            config.setVersion(doc.getString(FIELD_VERSION));
            config.setProvider(doc.getString(FIELD_PROVIDER));
            config.setUrl(doc.getString(FIELD_URL));
            config.setApiKey(doc.getString(FIELD_API_KEY));
            config.setDimension(doc.getInteger(FIELD_DIMENSION, 0));
            config.setTimeoutMillis(doc.getInteger(FIELD_TIMEOUT, 3000));
            config.setBatchSize(doc.getInteger(FIELD_BATCH_SIZE, 1));
            config.setDefault(doc.getBoolean(FIELD_IS_DEFAULT, false));
            result.add(config);
        }
        return result;
    }

    @Override
    public void save(VectorLiteProperties.ModelConfig config) {
        Document doc = new Document(FIELD_NAME, config.getName())
                .append(FIELD_VERSION, config.getVersion())
                .append(FIELD_PROVIDER, config.getProvider())
                .append(FIELD_URL, config.getUrl())
                .append(FIELD_API_KEY, config.getApiKey())
                .append(FIELD_DIMENSION, config.getDimension())
                .append(FIELD_TIMEOUT, config.getTimeoutMillis())
                .append(FIELD_BATCH_SIZE, config.getBatchSize())
                .append(FIELD_IS_DEFAULT, config.isDefault())
                .append(FIELD_UPDATED_AT, new Date());
        collection.replaceOne(
                Filters.and(Filters.eq(FIELD_NAME, config.getName()), Filters.eq(FIELD_VERSION, config.getVersion())),
                doc,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public boolean delete(String name, String version) {
        return collection.deleteOne(
                Filters.and(Filters.eq(FIELD_NAME, name), Filters.eq(FIELD_VERSION, version))).getDeletedCount() > 0;
    }

    @Override
    public void saveDefault(EmbeddingModelRef ref) {
        collection.updateMany(new Document(), new Document("$set", new Document(FIELD_IS_DEFAULT, false)));
        if (ref != null) {
            collection.updateOne(Filters.and(Filters.eq(FIELD_NAME, ref.name()), Filters.eq(FIELD_VERSION, ref.version())),
                    new Document("$set", new Document(FIELD_IS_DEFAULT, true)));
        }
    }

    @Override
    public EmbeddingModelRef loadDefault() {
        Document doc = collection.find(Filters.eq(FIELD_IS_DEFAULT, true)).first();
        return doc == null ? null : new EmbeddingModelRef(doc.getString(FIELD_NAME), doc.getString(FIELD_VERSION));
    }

    public void close() {
        mongoClient.close();
    }
}

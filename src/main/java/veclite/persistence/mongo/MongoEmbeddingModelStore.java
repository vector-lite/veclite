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
    private static final String FIELD_TIMEOUT = "timeout_millis";
    private static final String FIELD_BATCH_SIZE = "batch_size";
    private static final String FIELD_UPDATED_AT = "updated_at";
    private static final String FIELD_DEFAULT_NAME = "default_name";
    private static final String FIELD_DEFAULT_VERSION = "default_version";
    /** 旧版单字段默认标记（仅名称） */
    private static final String LEGACY_FIELD_DEFAULT_MODEL = "default_model";
    private static final String DEFAULT_MARKER_ID = "__default__";

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
        } catch (Exception ignored) {
            // 索引迁移失败只影响防重保护强度（upsert 仍按 name+version 过滤生效），不阻断应用启动
        }
    }

    @Override
    public List<VectorLiteProperties.ModelConfig> loadAll() {
        List<VectorLiteProperties.ModelConfig> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            if (DEFAULT_MARKER_ID.equals(doc.getString("_id"))) {
                continue;
            }
            VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
            config.setName(doc.getString(FIELD_NAME));
            config.setVersion(doc.getString(FIELD_VERSION));
            config.setProvider(doc.getString(FIELD_PROVIDER));
            config.setUrl(doc.getString(FIELD_URL));
            config.setTimeoutMillis(doc.getInteger(FIELD_TIMEOUT, 3000));
            config.setBatchSize(doc.getInteger(FIELD_BATCH_SIZE, 1));
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
                .append(FIELD_TIMEOUT, config.getTimeoutMillis())
                .append(FIELD_BATCH_SIZE, config.getBatchSize())
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
        if (ref == null) {
            collection.deleteOne(Filters.eq("_id", DEFAULT_MARKER_ID));
            return;
        }
        collection.replaceOne(Filters.eq("_id", DEFAULT_MARKER_ID),
                new Document("_id", DEFAULT_MARKER_ID)
                        .append(FIELD_DEFAULT_NAME, ref.name())
                        .append(FIELD_DEFAULT_VERSION, ref.version()),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public EmbeddingModelRef loadDefault() {
        Document doc = collection.find(Filters.eq("_id", DEFAULT_MARKER_ID)).first();
        if (doc == null) {
            return null;
        }
        String name = doc.getString(FIELD_DEFAULT_NAME);
        if (name != null) {
            return new EmbeddingModelRef(name, doc.getString(FIELD_DEFAULT_VERSION));
        }
        // 旧格式：仅存名称（单字段唯一键时代的标记），版本留空由注册中心解析主版本
        String legacyName = doc.getString(LEGACY_FIELD_DEFAULT_MODEL);
        return legacyName != null ? new EmbeddingModelRef(legacyName, null) : null;
    }

    public void close() {
        mongoClient.close();
    }
}

package veclite.persistence.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import veclite.api.VectorStoreMetadata;
import veclite.config.VectorLiteProperties;
import veclite.model.QuantizationType;
import veclite.model.StorageType;
import veclite.persistence.VectorDocumentEntity;
import veclite.persistence.VectorDocumentRepository;
import veclite.persistence.VectorStorageFormat;
import veclite.persistence.StoreNameValidator;
import veclite.persistence.StorePersistenceHandle;
import veclite.persistence.VectorStorageFormat;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * {@link VectorDocumentRepository} 的 PostgreSQL 适配器（v2.5 单一真相源持久化）。
 * <p>
 * 表结构见 {@code src/main/resources/schema/veclite_postgres.sql}：
 * <ul>
 *   <li>文档表：主键 {@code (store_name, doc_id)}，向量以 BYTEA 存储
 *       （避免 JSON 数组或 text 编码带来的数倍膨胀）；</li>
 *   <li>元数据表：主键 {@code store_name}，1 库 1 行。</li>
 * </ul>
 *
 * <p><b>流式扫描</b>：JDBC 无法在连接关闭后继续消费 ResultSet，因此 {@link #scan} 采用
 * 基于主键的 keyset 分页（{@code doc_id > ? ORDER BY doc_id LIMIT ?}）逐批取回，
 * 既避免 OFFSET 分页的深翻页退化，也把单次内存占用控制在 {@code fetchSize} 条以内。
 */
public class PostgresVectorDocumentRepository implements VectorDocumentRepository {

    private static final String FIELD_STORE_NAME = "store_name";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_DOC_TEXT = "doc_text";
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

    /** 单条 DELETE ... IN 语句携带的 ID 上限，避免超长 SQL 与计划缓存膨胀 */
    private static final int DELETE_CHUNK_SIZE = 1000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String metaTable;
    private final int fetchSize;

    public PostgresVectorDocumentRepository(VectorLiteProperties properties) {
        this(properties, createDataSource(properties));
    }

    public PostgresVectorDocumentRepository(VectorLiteProperties properties, DataSource dataSource) {
        VectorLiteProperties.PostgresConfig config = properties.getStorage().getPostgres();
        this.jdbc = new JdbcTemplate(dataSource);
        this.metaTable = config.getMetaTable();
        this.fetchSize = config.getFetchSize() > 0 ? config.getFetchSize() : 1000;
        ensureSchema();
    }

    /**
     * 自建轻量数据源：作为 SDK 内嵌的持久化组件，不复用业务方的 {@code spring.datasource}，
     * 避免与应用主数据源的事务管理、连接池配置相互干扰。
     */
    private static DataSource createDataSource(VectorLiteProperties properties) {
        VectorLiteProperties.PostgresConfig config = properties.getStorage().getPostgres();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(config.getJdbcUrl());
        dataSource.setUsername(config.getUsername());
        dataSource.setPassword(config.getPassword());
        return dataSource;
    }

    @Override
    public void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + metaTable + " ("
                + FIELD_STORE_NAME + "              VARCHAR(128) PRIMARY KEY, "
                + META_DIMENSION + "                INT NOT NULL, "
                + META_METRIC + "                   VARCHAR(32) NOT NULL DEFAULT 'COSINE', "
                + META_MAX_CAPACITY + "             INT NOT NULL DEFAULT 100000, "
                + META_EMBEDDING_MODEL + "          VARCHAR(128), "
                + META_EMBEDDING_MODEL_VERSION + "  VARCHAR(64), "
                + META_QUANTIZATION + "             VARCHAR(32) NOT NULL DEFAULT 'NONE', "
                + META_INDEXED_FIELDS + "           JSONB, "
                + META_PERSISTENCE_MODE + "         VARCHAR(32), "
                + META_ACTIVE_COUNT + "             INT DEFAULT 0, "
                + META_SQ8_MIN + "                  BYTEA, "
                + META_SQ8_SCALE + "                BYTEA, "
                + META_CREATED_AT + "               TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, "
                + FIELD_UPDATED_AT + "              TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)");
    }

    @Override
    public StorePersistenceHandle ensureStore(String storeName) {
        StoreNameValidator.validate(storeName);
        String table = storeName;
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + table + "\" ("
                + FIELD_DOC_ID + "          VARCHAR(256) NOT NULL, "
                + FIELD_DOC_TEXT + "        TEXT, "
                + FIELD_METADATA + "        JSONB, "
                + FIELD_VECTOR + "          BYTEA, "
                + FIELD_UPDATED_AT + "      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (" + FIELD_DOC_ID + "))");
        return new StorePersistenceHandle(storeName, table);
    }

    @Override
    public StorePersistenceHandle handle(String storeName) {
        StoreNameValidator.validate(storeName);
        return new StorePersistenceHandle(storeName, storeName);
    }

    @Override
    public void dropStore(String storeName) {
        StoreNameValidator.validate(storeName);
        jdbc.execute("DROP TABLE IF EXISTS \"" + storeName + "\"");
        deleteStoreMetadata(storeName);
    }

    @Override
    public void upsertBatch(String storeName, List<VectorDocumentEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        String table = ensureStore(storeName).physicalName();
        String sql = "INSERT INTO \"" + table + "\" ("
                + FIELD_DOC_ID + ", " + FIELD_DOC_TEXT + ", " + FIELD_METADATA + ", "
                + FIELD_VECTOR + ", " + FIELD_UPDATED_AT + ") "
                + "VALUES (?, ?::jsonb, ?, ?, ?) "
                + "ON CONFLICT (" + FIELD_DOC_ID + ") DO UPDATE SET "
                + FIELD_DOC_TEXT + "=EXCLUDED." + FIELD_DOC_TEXT + ", "
                + FIELD_METADATA + "=EXCLUDED." + FIELD_METADATA + ", "
                + FIELD_VECTOR + "=EXCLUDED." + FIELD_VECTOR + ", "
                + FIELD_UPDATED_AT + "=CURRENT_TIMESTAMP";

        List<Object[]> batch = new ArrayList<>(entities.size());
        for (VectorDocumentEntity entity : entities) {
            batch.add(new Object[]{
                    entity.getDocId(),
                    entity.getText(),
                    toJson(entity.getMetadata()),
                    toVectorBytes(entity),
                    new Timestamp(System.currentTimeMillis())
            });
        }
        jdbc.batchUpdate(sql, batch);
    }

    @Override
    public long deleteByIds(String storeName, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return 0;
        }
        long deleted = 0;
        for (int from = 0; from < documentIds.size(); from += DELETE_CHUNK_SIZE) {
            int to = Math.min(from + DELETE_CHUNK_SIZE, documentIds.size());
            List<String> chunk = documentIds.subList(from, to);
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Object[] args = new Object[chunk.size() + 1];
            System.arraycopy(chunk.toArray(), 0, args, 0, chunk.size());
            deleted += jdbc.update("DELETE FROM \"" + handle(storeName).physicalName()
                    + "\" WHERE " + FIELD_DOC_ID + " IN (" + placeholders + ")", args);
        }
        return deleted;
    }

    @Override
    public long deleteAll(String storeName) {
        return jdbc.update("DELETE FROM \"" + handle(storeName).physicalName() + "\"", new Object[0]);
    }

    @Override
    public Iterator<VectorDocumentEntity> scan(String storeName) {
        VectorStorageFormat format = VectorStorageFormat.FLOAT32;
        return new DocumentIterator(storeName, format);
    }

    @Override
    public List<String> listDocumentIds(String storeName) {
        return jdbc.queryForList("SELECT " + FIELD_DOC_ID + " FROM \"" + handle(storeName).physicalName()
                + "\"", String.class);
    }

    @Override
    public long count(String storeName) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + handle(storeName).physicalName()
                + "\"", Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public void saveStoreMetadata(VectorStoreMetadata metadata) {
        String sql = "INSERT INTO " + metaTable + " ("
                + FIELD_STORE_NAME + ", " + META_DIMENSION + ", " + META_METRIC + ", " + META_MAX_CAPACITY + ", "
                + META_EMBEDDING_MODEL + ", " + META_EMBEDDING_MODEL_VERSION + ", " + META_QUANTIZATION + ", "
                + META_INDEXED_FIELDS + ", " + META_PERSISTENCE_MODE + ", " + META_ACTIVE_COUNT + ", "
                + META_SQ8_MIN + ", " + META_SQ8_SCALE + ", " + META_CREATED_AT + ", " + FIELD_UPDATED_AT + ") "
                + "VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?) "
                + "ON CONFLICT (" + FIELD_STORE_NAME + ") DO UPDATE SET "
                + META_DIMENSION + "=EXCLUDED." + META_DIMENSION + ", "
                + META_METRIC + "=EXCLUDED." + META_METRIC + ", "
                + META_MAX_CAPACITY + "=EXCLUDED." + META_MAX_CAPACITY + ", "
                + META_EMBEDDING_MODEL + "=EXCLUDED." + META_EMBEDDING_MODEL + ", "
                + META_EMBEDDING_MODEL_VERSION + "=EXCLUDED." + META_EMBEDDING_MODEL_VERSION + ", "
                + META_QUANTIZATION + "=EXCLUDED." + META_QUANTIZATION + ", "
                + META_INDEXED_FIELDS + "=EXCLUDED." + META_INDEXED_FIELDS + ", "
                + META_PERSISTENCE_MODE + "=EXCLUDED." + META_PERSISTENCE_MODE + ", "
                + META_ACTIVE_COUNT + "=EXCLUDED." + META_ACTIVE_COUNT + ", "
                + META_SQ8_MIN + "=EXCLUDED." + META_SQ8_MIN + ", "
                + META_SQ8_SCALE + "=EXCLUDED." + META_SQ8_SCALE + ", "
                + FIELD_UPDATED_AT + "=CURRENT_TIMESTAMP";

        jdbc.update(sql,
                metadata.getStoreName(),
                metadata.getDimension(),
                metadata.getMetric(),
                metadata.getMaxCapacity(),
                metadata.getEmbeddingModel(),
                metadata.getEmbeddingModelVersion(),
                metadata.getQuantization() != null ? metadata.getQuantization().name() : QuantizationType.NONE.name(),
                toJson(metadata.getIndexedMetadataFields()),
                metadata.getPersistenceMode() != null ? metadata.getPersistenceMode().name() : StorageType.POSTGRES.name(),
                metadata.getActiveCount(),
                metadata.getSq8MinPerDim() != null ? VectorDocumentEntity.encodeVector(metadata.getSq8MinPerDim()) : null,
                metadata.getSq8ScalePerDim() != null ? VectorDocumentEntity.encodeVector(metadata.getSq8ScalePerDim()) : null,
                Timestamp.from(metadata.getCreatedAt() != null ? metadata.getCreatedAt() : Instant.now()),
                Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<VectorStoreMetadata> findStoreMetadata(String storeName) {
        List<VectorStoreMetadata> rows = jdbc.query(
                "SELECT * FROM " + metaTable + " WHERE " + FIELD_STORE_NAME + " = ?",
                (rs, rowNum) -> {
                    VectorStoreMetadata metadata = new VectorStoreMetadata();
                    metadata.setStoreName(rs.getString(FIELD_STORE_NAME));
                    metadata.setDimension(rs.getInt(META_DIMENSION));
                    metadata.setMetric(rs.getString(META_METRIC));
                    metadata.setMaxCapacity(rs.getInt(META_MAX_CAPACITY));
                    metadata.setEmbeddingModel(rs.getString(META_EMBEDDING_MODEL));
                    metadata.setEmbeddingModelVersion(rs.getString(META_EMBEDDING_MODEL_VERSION));
                    metadata.setQuantization(parseQuantization(rs.getString(META_QUANTIZATION)));
                    metadata.setIndexedMetadataFields(fromJsonList(rs.getString(META_INDEXED_FIELDS)));
                    metadata.setPersistenceMode(parseStorageType(rs.getString(META_PERSISTENCE_MODE)));
                    metadata.setActiveCount(rs.getInt(META_ACTIVE_COUNT));
                    byte[] sq8Min = rs.getBytes(META_SQ8_MIN);
                    byte[] sq8Scale = rs.getBytes(META_SQ8_SCALE);
                    if (sq8Min != null && sq8Scale != null) {
                        metadata.setSq8MinPerDim(VectorDocumentEntity.decodeVector(sq8Min));
                        metadata.setSq8ScalePerDim(VectorDocumentEntity.decodeVector(sq8Scale));
                    }
                    Timestamp createdAt = rs.getTimestamp(META_CREATED_AT);
                    metadata.setCreatedAt(createdAt != null ? createdAt.toInstant() : null);
                    Timestamp updatedAt = rs.getTimestamp(FIELD_UPDATED_AT);
                    metadata.setUpdatedAt(updatedAt != null ? updatedAt.toInstant() : null);
                    return metadata;
                },
                storeName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<VectorStoreMetadata> listStoreMetadata() {
        return jdbc.query("SELECT * FROM " + metaTable, (rs, rowNum) -> {
            VectorStoreMetadata metadata = new VectorStoreMetadata();
            metadata.setStoreName(rs.getString(FIELD_STORE_NAME));
            metadata.setDimension(rs.getInt(META_DIMENSION));
            metadata.setMetric(rs.getString(META_METRIC));
            metadata.setMaxCapacity(rs.getInt(META_MAX_CAPACITY));
            metadata.setEmbeddingModel(rs.getString(META_EMBEDDING_MODEL));
            metadata.setEmbeddingModelVersion(rs.getString(META_EMBEDDING_MODEL_VERSION));
            metadata.setQuantization(parseQuantization(rs.getString(META_QUANTIZATION)));
            metadata.setIndexedMetadataFields(fromJsonList(rs.getString(META_INDEXED_FIELDS)));
            metadata.setPersistenceMode(parseStorageType(rs.getString(META_PERSISTENCE_MODE)));
            metadata.setActiveCount(rs.getInt(META_ACTIVE_COUNT));
            byte[] sq8Min = rs.getBytes(META_SQ8_MIN);
            byte[] sq8Scale = rs.getBytes(META_SQ8_SCALE);
            if (sq8Min != null && sq8Scale != null) {
                metadata.setSq8MinPerDim(VectorDocumentEntity.decodeVector(sq8Min));
                metadata.setSq8ScalePerDim(VectorDocumentEntity.decodeVector(sq8Scale));
            }
            Timestamp createdAt = rs.getTimestamp(META_CREATED_AT);
            metadata.setCreatedAt(createdAt != null ? createdAt.toInstant() : null);
            Timestamp updatedAt = rs.getTimestamp(FIELD_UPDATED_AT);
            metadata.setUpdatedAt(updatedAt != null ? updatedAt.toInstant() : null);
            return metadata;
        });
    }

    @Override
    public void deleteStoreMetadata(String storeName) {
        jdbc.update("DELETE FROM " + metaTable + " WHERE " + FIELD_STORE_NAME + " = ?", storeName);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内置数据源是 {@link DriverManagerDataSource}（无连接池），连接随用随建、由 JDBC 自行回收，
     * 因此这里无需显式释放；若调用方通过构造器注入了自己的数据源，生命周期仍归调用方所有。
     */
    @Override
    public void close() {
        // 无连接池需要关闭
    }

    private byte[] toVectorBytes(VectorDocumentEntity entity) {
        return entity.getFormat() == VectorStorageFormat.SQ8
                ? entity.getSq8Vector()
                : VectorDocumentEntity.encodeVector(entity.getVector());
    }

    private QuantizationType parseQuantization(String name) {
        if (name == null) {
            return QuantizationType.NONE;
        }
        try {
            return QuantizationType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown quantization type in store metadata: " + name, e);
        }
    }

    private StorageType parseStorageType(String name) {
        if (name == null) {
            return StorageType.POSTGRES;
        }
        try {
            return StorageType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown persistence mode in store metadata: " + name, e);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize metadata to JSONB", e);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize indexed_metadata_fields from JSONB", e);
        }
    }

    /**
     * 基于主键的 keyset 分页迭代器：每批取 {@code fetchSize} 条，取完再取下一批，
     * 全程只保留一个批次的文档在内存中。
     */
    private final class DocumentIterator implements Iterator<VectorDocumentEntity> {

        private final String storeName;
        private final VectorStorageFormat format;
        private List<VectorDocumentEntity> buffer = Collections.emptyList();
        private int cursor;
        private String lastDocId;
        private boolean exhausted;

        private DocumentIterator(String storeName, VectorStorageFormat format) {
            this.storeName = storeName;
            this.format = format;
        }

        @Override
        public boolean hasNext() {
            if (cursor < buffer.size()) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            fetchNextChunk();
            return cursor < buffer.size();
        }

        @Override
        public VectorDocumentEntity next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more documents for store [" + storeName + "]");
            }
            return buffer.get(cursor++);
        }

        private void fetchNextChunk() {
            StringBuilder sql = new StringBuilder("SELECT ")
                    .append(FIELD_DOC_ID).append(", ").append(FIELD_DOC_TEXT).append(", ")
                    .append(FIELD_METADATA).append(", ").append(FIELD_VECTOR).append(", ").append(FIELD_UPDATED_AT)
                    .append(" FROM \"").append(handle(storeName).physicalName()).append("\"")
                    .append(" WHERE 1=1");
            if (lastDocId != null) {
                sql.append(" AND ").append(FIELD_DOC_ID).append(" > ?");
            }
            sql.append(" ORDER BY ").append(FIELD_DOC_ID).append(" LIMIT ?");

            Object[] args = lastDocId == null
                    ? new Object[]{fetchSize}
                    : new Object[]{lastDocId, fetchSize};

            buffer = jdbc.query(sql.toString(), (rs, rowNum) -> {
                VectorDocumentEntity entity = new VectorDocumentEntity();
                entity.setDocId(rs.getString(FIELD_DOC_ID));
                entity.setText(rs.getString(FIELD_DOC_TEXT));
                entity.setMetadata(fromJsonMap(rs.getString(FIELD_METADATA)));
                byte[] vector = rs.getBytes(FIELD_VECTOR);
                if (vector != null) {
                    entity.setFormat(format);
                    if (format == VectorStorageFormat.SQ8) {
                        entity.setSq8Vector(vector);
                    } else {
                        entity.setVector(VectorDocumentEntity.decodeVector(vector));
                    }
                }
                Timestamp updatedAt = rs.getTimestamp(FIELD_UPDATED_AT);
                entity.setUpdatedAt(updatedAt != null ? updatedAt.toInstant() : null);
                return entity;
            }, args);

            cursor = 0;
            if (buffer.isEmpty()) {
                exhausted = true;
            } else {
                lastDocId = buffer.get(buffer.size() - 1).getDocId();
            }
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

        @SuppressWarnings("unchecked")
        private java.util.Map<String, Object> fromJsonMap(String json) {
            if (json == null || json.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readValue(json, java.util.Map.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize document metadata from JSONB", e);
            }
        }
    }
}

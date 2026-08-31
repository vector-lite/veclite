package veclite.persistence.postgres;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import veclite.config.VectorLiteProperties;
import veclite.embedding.EmbeddingModelRef;
import veclite.embedding.EmbeddingModelStore;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@link EmbeddingModelStore} 的 PostgreSQL 实现：托管模型配置存储于
 * {@code veclite_embedding_model} 表，主键为（name, version）复合主键；
 * "默认模型"标记单独存于 {@code veclite_embedding_default} 表的单行记录，
 * 与 MongoDB 实现的语义完全一致。
 */
public class PostgresEmbeddingModelStore implements EmbeddingModelStore {

    private static final String FIELD_NAME = "name";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_URL = "url";
    private static final String FIELD_PROVIDER = "provider";
    private static final String FIELD_API_KEY = "api_key";
    private static final String FIELD_DIMENSION = "dimension";
    private static final String FIELD_TIMEOUT = "timeout_millis";
    private static final String FIELD_BATCH_SIZE = "batch_size";
    private static final String FIELD_UPDATED_AT = "updated_at";

    private static final String DEFAULT_MARKER_ID = "__default__";
    private static final String FIELD_MARKER_ID = "marker_id";
    private static final String FIELD_DEFAULT_NAME = "default_name";
    private static final String FIELD_DEFAULT_VERSION = "default_version";

    private final JdbcTemplate jdbc;
    private final String modelTable;
    private final String defaultTable;

    public PostgresEmbeddingModelStore(VectorLiteProperties properties) {
        this(properties, createDataSource(properties));
    }

    public PostgresEmbeddingModelStore(VectorLiteProperties properties, DataSource dataSource) {
        VectorLiteProperties.PostgresConfig config = properties.getStorage().getPostgres();
        this.jdbc = new JdbcTemplate(dataSource);
        this.modelTable = config.getEmbeddingModelTable();
        this.defaultTable = "veclite_embedding_default";
        ensureSchema();
    }

    private static DataSource createDataSource(VectorLiteProperties properties) {
        VectorLiteProperties.PostgresConfig config = properties.getStorage().getPostgres();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(config.getJdbcUrl());
        dataSource.setUsername(config.getUsername());
        dataSource.setPassword(config.getPassword());
        return dataSource;
    }

    private void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + modelTable + " ("
                + FIELD_NAME + "        VARCHAR(128) NOT NULL, "
                + FIELD_VERSION + "     VARCHAR(64) NOT NULL, "
                + FIELD_PROVIDER + "    VARCHAR(64), "
                + FIELD_URL + "         TEXT, "
                + FIELD_API_KEY + "     TEXT, "
                + FIELD_DIMENSION + "   INT DEFAULT 0, "
                + FIELD_TIMEOUT + "     INT DEFAULT 3000, "
                + FIELD_BATCH_SIZE + "  INT DEFAULT 1, "
                + FIELD_UPDATED_AT + "  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (" + FIELD_NAME + ", " + FIELD_VERSION + "))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS " + defaultTable + " ("
                + FIELD_MARKER_ID + "       VARCHAR(32) PRIMARY KEY, "
                + FIELD_DEFAULT_NAME + "    VARCHAR(128), "
                + FIELD_DEFAULT_VERSION + " VARCHAR(64))");
    }

    @Override
    public List<VectorLiteProperties.ModelConfig> loadAll() {
        return jdbc.query("SELECT " + FIELD_NAME + ", " + FIELD_VERSION + ", " + FIELD_PROVIDER + ", "
                + FIELD_URL + ", " + FIELD_API_KEY + ", " + FIELD_DIMENSION + ", "
                + FIELD_TIMEOUT + ", " + FIELD_BATCH_SIZE + " FROM " + modelTable,
                (rs, rowNum) -> {
                    VectorLiteProperties.ModelConfig config = new VectorLiteProperties.ModelConfig();
                    config.setName(rs.getString(FIELD_NAME));
                    config.setVersion(rs.getString(FIELD_VERSION));
                    config.setProvider(rs.getString(FIELD_PROVIDER));
                    config.setUrl(rs.getString(FIELD_URL));
                    config.setApiKey(rs.getString(FIELD_API_KEY));
                    config.setDimension(rs.getInt(FIELD_DIMENSION));
                    config.setTimeoutMillis(rs.getInt(FIELD_TIMEOUT));
                    config.setBatchSize(rs.getInt(FIELD_BATCH_SIZE));
                    return config;
                });
    }

    @Override
    public void save(VectorLiteProperties.ModelConfig config) {
        jdbc.update("INSERT INTO " + modelTable + " ("
                + FIELD_NAME + ", " + FIELD_VERSION + ", " + FIELD_PROVIDER + ", " + FIELD_URL + ", "
                + FIELD_API_KEY + ", " + FIELD_DIMENSION + ", " + FIELD_TIMEOUT + ", " + FIELD_BATCH_SIZE + ", "
                + FIELD_UPDATED_AT + ") VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT (" + FIELD_NAME + ", " + FIELD_VERSION + ") DO UPDATE SET "
                + FIELD_PROVIDER + "=EXCLUDED." + FIELD_PROVIDER + ", "
                + FIELD_URL + "=EXCLUDED." + FIELD_URL + ", "
                + FIELD_API_KEY + "=EXCLUDED." + FIELD_API_KEY + ", "
                + FIELD_DIMENSION + "=EXCLUDED." + FIELD_DIMENSION + ", "
                + FIELD_TIMEOUT + "=EXCLUDED." + FIELD_TIMEOUT + ", "
                + FIELD_BATCH_SIZE + "=EXCLUDED." + FIELD_BATCH_SIZE + ", "
                + FIELD_UPDATED_AT + "=CURRENT_TIMESTAMP",
                config.getName(), config.getVersion(), config.getProvider(), config.getUrl(),
                config.getApiKey(), config.getDimension(), config.getTimeoutMillis(), config.getBatchSize());
    }

    @Override
    public boolean delete(String name, String version) {
        return jdbc.update("DELETE FROM " + modelTable
                + " WHERE " + FIELD_NAME + " = ? AND " + FIELD_VERSION + " = ?", name, version) > 0;
    }

    @Override
    public void saveDefault(EmbeddingModelRef ref) {
        if (ref == null) {
            jdbc.update("DELETE FROM " + defaultTable + " WHERE " + FIELD_MARKER_ID + " = ?", DEFAULT_MARKER_ID);
            return;
        }
        jdbc.update("INSERT INTO " + defaultTable + " ("
                + FIELD_MARKER_ID + ", " + FIELD_DEFAULT_NAME + ", " + FIELD_DEFAULT_VERSION + ") "
                + "VALUES (?,?,?) ON CONFLICT (" + FIELD_MARKER_ID + ") DO UPDATE SET "
                + FIELD_DEFAULT_NAME + "=EXCLUDED." + FIELD_DEFAULT_NAME + ", "
                + FIELD_DEFAULT_VERSION + "=EXCLUDED." + FIELD_DEFAULT_VERSION,
                DEFAULT_MARKER_ID, ref.name(), ref.version());
    }

    @Override
    public EmbeddingModelRef loadDefault() {
        List<EmbeddingModelRef> rows = jdbc.query("SELECT " + FIELD_DEFAULT_NAME + ", " + FIELD_DEFAULT_VERSION
                + " FROM " + defaultTable + " WHERE " + FIELD_MARKER_ID + " = ?",
                (rs, rowNum) -> new EmbeddingModelRef(rs.getString(FIELD_DEFAULT_NAME),
                        rs.getString(FIELD_DEFAULT_VERSION)),
                DEFAULT_MARKER_ID);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 无连接池需要释放，生命周期随注入的数据源 */
    public void close() {
        // 与 PostgresVectorDocumentRepository#close 一致：内置数据源不含连接池
    }
}

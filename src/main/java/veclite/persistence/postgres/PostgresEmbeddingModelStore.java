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
 * 全局默认模型通过该表的 {@code is_default} 字段标记。
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

    private static final String FIELD_IS_DEFAULT = "is_default";

    private final JdbcTemplate jdbc;
    private final String modelTable;

    public PostgresEmbeddingModelStore(VectorLiteProperties properties) {
        this(properties, createDataSource(properties));
    }

    public PostgresEmbeddingModelStore(VectorLiteProperties properties, DataSource dataSource) {
        VectorLiteProperties.PostgresConfig config = properties.getStorage().getPostgres();
        this.jdbc = new JdbcTemplate(dataSource);
        this.modelTable = config.getEmbeddingModelTable();
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
                + FIELD_IS_DEFAULT + "   BOOLEAN NOT NULL DEFAULT FALSE, "
                + FIELD_UPDATED_AT + "  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (" + FIELD_NAME + ", " + FIELD_VERSION + "))");
        jdbc.execute("ALTER TABLE " + modelTable + " ADD COLUMN IF NOT EXISTS " + FIELD_IS_DEFAULT + " BOOLEAN NOT NULL DEFAULT FALSE");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_" + modelTable + "_default ON " + modelTable + " (" + FIELD_IS_DEFAULT + ") WHERE " + FIELD_IS_DEFAULT + " = TRUE");
    }

    @Override
    public List<VectorLiteProperties.ModelConfig> loadAll() {
        return jdbc.query("SELECT " + FIELD_NAME + ", " + FIELD_VERSION + ", " + FIELD_PROVIDER + ", "
                + FIELD_URL + ", " + FIELD_API_KEY + ", " + FIELD_DIMENSION + ", "
                + FIELD_TIMEOUT + ", " + FIELD_BATCH_SIZE + ", " + FIELD_IS_DEFAULT + " FROM " + modelTable,
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
                    config.setDefault(rs.getBoolean(FIELD_IS_DEFAULT));
                    return config;
                });
    }

    @Override
    public void save(VectorLiteProperties.ModelConfig config) {
        jdbc.update("INSERT INTO " + modelTable + " ("
                + FIELD_NAME + ", " + FIELD_VERSION + ", " + FIELD_PROVIDER + ", " + FIELD_URL + ", "
                + FIELD_API_KEY + ", " + FIELD_DIMENSION + ", " + FIELD_TIMEOUT + ", " + FIELD_BATCH_SIZE + ", " + FIELD_IS_DEFAULT + ", "
                + FIELD_UPDATED_AT + ") VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT (" + FIELD_NAME + ", " + FIELD_VERSION + ") DO UPDATE SET "
                + FIELD_PROVIDER + "=EXCLUDED." + FIELD_PROVIDER + ", "
                + FIELD_URL + "=EXCLUDED." + FIELD_URL + ", "
                + FIELD_API_KEY + "=EXCLUDED." + FIELD_API_KEY + ", "
                + FIELD_DIMENSION + "=EXCLUDED." + FIELD_DIMENSION + ", "
                + FIELD_TIMEOUT + "=EXCLUDED." + FIELD_TIMEOUT + ", "
                + FIELD_BATCH_SIZE + "=EXCLUDED." + FIELD_BATCH_SIZE + ", "
                + FIELD_IS_DEFAULT + "=EXCLUDED." + FIELD_IS_DEFAULT + ", "
                + FIELD_UPDATED_AT + "=CURRENT_TIMESTAMP",
                config.getName(), config.getVersion(), config.getProvider(), config.getUrl(),
                config.getApiKey(), config.getDimension(), config.getTimeoutMillis(), config.getBatchSize(), config.isDefault());
    }

    @Override
    public boolean delete(String name, String version) {
        return jdbc.update("DELETE FROM " + modelTable
                + " WHERE " + FIELD_NAME + " = ? AND " + FIELD_VERSION + " = ?", name, version) > 0;
    }

    @Override
    public void saveDefault(EmbeddingModelRef ref) {
        jdbc.update("UPDATE " + modelTable + " SET " + FIELD_IS_DEFAULT + " = FALSE");
        if (ref != null) {
            jdbc.update("UPDATE " + modelTable + " SET " + FIELD_IS_DEFAULT + " = TRUE WHERE " + FIELD_NAME + " = ? AND " + FIELD_VERSION + " = ?", ref.name(), ref.version());
        }
    }

    @Override
    public EmbeddingModelRef loadDefault() {
        List<EmbeddingModelRef> rows = jdbc.query("SELECT " + FIELD_NAME + ", " + FIELD_VERSION + " FROM " + modelTable + " WHERE " + FIELD_IS_DEFAULT + " = TRUE",
                (rs, rowNum) -> new EmbeddingModelRef(rs.getString(FIELD_NAME), rs.getString(FIELD_VERSION)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 无连接池需要释放，生命周期随注入的数据源 */
    public void close() {
        // 与 PostgresVectorDocumentRepository#close 一致：内置数据源不含连接池
    }
}

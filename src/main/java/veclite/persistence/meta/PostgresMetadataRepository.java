package veclite.persistence.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import veclite.model.QuantizationType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PostgresMetadataRepository: veclite_store_meta 表的默认实现。
 * 启用条件: veclite.storage.metadata.type=POSTGRES
 */
@Repository
@ConditionalOnProperty(name = "veclite.storage.metadata.type", havingValue = "POSTGRES", matchIfMissing = true)
public class PostgresMetadataRepository implements VectorMetadataRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresMetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(VectorStoreMetadata metadata) {
        String sql = "INSERT INTO veclite_store_meta " +
                "(store_name, dimension, metric, max_capacity, embedding_model, embedding_model_version, " +
                "quantization, indexed_metadata_fields, sq8_min_per_dim, sq8_scale_per_dim, " +
                "latest_snapshot_version, latest_snapshot_oss_path, active_count, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?) " +
                "ON CONFLICT (store_name) DO UPDATE SET " +
                "dimension=EXCLUDED.dimension, metric=EXCLUDED.metric, max_capacity=EXCLUDED.max_capacity, " +
                "embedding_model=EXCLUDED.embedding_model, embedding_model_version=EXCLUDED.embedding_model_version, " +
                "quantization=EXCLUDED.quantization, indexed_metadata_fields=EXCLUDED.indexed_metadata_fields, " +
                "updated_at=CURRENT_TIMESTAMP";
        jdbc.update(sql,
                metadata.getStoreName(),
                metadata.getDimension(),
                metadata.getMetric(),
                metadata.getMaxCapacity(),
                metadata.getEmbeddingModel(),
                metadata.getEmbeddingModelVersion(),
                metadata.getQuantization() == null ? "NONE" : metadata.getQuantization().name(),
                toJson(metadata.getIndexedMetadataFields()),
                metadata.getSq8MinPerDim(),
                metadata.getSq8ScalePerDim(),
                metadata.getLatestSnapshotVersion(),
                metadata.getLatestSnapshotOssPath(),
                metadata.getActiveCount(),
                Timestamp.from(metadata.getCreatedAt() == null ? Instant.now() : metadata.getCreatedAt()),
                Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<VectorStoreMetadata> findByName(String storeName) {
        List<VectorStoreMetadata> list = jdbc.query(
                "SELECT * FROM veclite_store_meta WHERE store_name = ?",
                rowMapper(), storeName);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<VectorStoreMetadata> listAll() {
        return jdbc.query("SELECT * FROM veclite_store_meta", rowMapper());
    }

    @Override
    public void updateSnapshotPointer(String storeName, String snapshotVersion, String ossPath, int activeCount) {
        jdbc.update(
                "UPDATE veclite_store_meta SET latest_snapshot_version = ?, latest_snapshot_oss_path = ?, " +
                        "active_count = ?, updated_at = CURRENT_TIMESTAMP WHERE store_name = ?",
                snapshotVersion, ossPath, activeCount, storeName);
    }

    @Override
    public void deleteByName(String storeName) {
        jdbc.update("DELETE FROM veclite_store_meta WHERE store_name = ?", storeName);
    }

    private RowMapper<VectorStoreMetadata> rowMapper() {
        return (ResultSet rs, int rowNum) -> {
            VectorStoreMetadata m = new VectorStoreMetadata();
            m.setStoreName(rs.getString("store_name"));
            m.setDimension(rs.getInt("dimension"));
            m.setMetric(rs.getString("metric"));
            m.setMaxCapacity(rs.getInt("max_capacity"));
            m.setEmbeddingModel(rs.getString("embedding_model"));
            m.setEmbeddingModelVersion(rs.getString("embedding_model_version"));
            m.setQuantization(QuantizationType.valueOf(rs.getString("quantization")));
            m.setIndexedMetadataFields(fromJson(rs.getString("indexed_metadata_fields")));
            m.setSq8MinPerDim(rs.getBytes("sq8_min_per_dim"));
            m.setSq8ScalePerDim(rs.getBytes("sq8_scale_per_dim"));
            m.setLatestSnapshotVersion(rs.getString("latest_snapshot_version"));
            m.setLatestSnapshotOssPath(rs.getString("latest_snapshot_oss_path"));
            m.setActiveCount(rs.getInt("active_count"));
            Timestamp c = rs.getTimestamp("created_at");
            Timestamp u = rs.getTimestamp("updated_at");
            m.setCreatedAt(c == null ? null : c.toInstant());
            m.setUpdatedAt(u == null ? null : u.toInstant());
            return m;
        };
    }

    private String toJson(List<String> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("indexed_metadata_fields 序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("indexed_metadata_fields 反序列化失败", e);
        }
    }
}

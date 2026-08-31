-- v2.4 hybrid persistence: 元数据表（PG 唯一权威）
-- 来自 src/main/resources/design/v2.4/hybrid_persistence_design.md 第 4.2 节
CREATE TABLE IF NOT EXISTS veclite_store_meta (
    store_name                  VARCHAR(128) PRIMARY KEY,
    dimension                   INT NOT NULL,
    metric                      VARCHAR(32) NOT NULL DEFAULT 'COSINE',
    max_capacity                INT NOT NULL DEFAULT 100000,
    embedding_model             VARCHAR(128),
    embedding_model_version     VARCHAR(64),
    quantization                VARCHAR(32) NOT NULL DEFAULT 'NONE',
    indexed_metadata_fields     JSONB,
    sq8_min_per_dim             BYTEA,
    sq8_scale_per_dim           BYTEA,
    latest_snapshot_version     VARCHAR(64),
    latest_snapshot_oss_path    VARCHAR(512),
    active_count                INT DEFAULT 0,
    created_at                  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_veclite_meta_updated ON veclite_store_meta (updated_at);

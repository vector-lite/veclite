-- VecLite PostgreSQL 单一真相源持久化 建表脚本
--
-- 说明：
-- 1) 应用启动时 PostgresVectorDocumentRepository#ensureSchema 会以 CREATE TABLE IF NOT EXISTS
--    自动建表，本脚本供 DBA 预建 / 审阅 / 加分区与权限时使用，两者结构必须保持一致。
-- 2) 向量一律以 BYTEA 存储：FLOAT32 为原始 float[] 的小端序列化（4 字节/维），
--    SQ8 为量化字节（1 字节/维）。禁止使用 JSON 数组或文本编码，会带来数倍膨胀。
-- 3) 每个 Store 使用以 storeName 命名的独立文档表；Store 名称由接口层校验。

-- 文档真相源表模板：应用按 Store 动态创建，不创建共享 veclite_document
/* CREATE TABLE "<storeName>" (
    doc_id          VARCHAR(256) NOT NULL,
    doc_text        TEXT,
    metadata        JSONB,
    vector          BYTEA,
    updated_at      TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (doc_id)
); */

-- Store 级元数据：1 库 1 行
CREATE TABLE IF NOT EXISTS veclite_store_meta (
    store_name                  VARCHAR(128) PRIMARY KEY,
    dimension                   INT          NOT NULL,
    metric                      VARCHAR(32)  NOT NULL DEFAULT 'COSINE',
    max_capacity                INT          NOT NULL DEFAULT 100000,
    embedding_model             VARCHAR(128),
    embedding_model_version     VARCHAR(64),
    quantization                VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    indexed_metadata_fields     JSONB,
    persistence_mode            VARCHAR(32),          -- 数据位置记录：MONGODB | POSTGRES | SNAPSHOT_FILE
    active_count                INT          DEFAULT 0,
    sq8_min_per_dim             BYTEA,                -- SQ8 冻结态逐维 min（float[] 小端）
    sq8_scale_per_dim           BYTEA,                -- SQ8 冻结态逐维 scale（float[] 小端）
    created_at                  TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP
);

-- Embedding 数据源：数据库维护，(name, version) 复合主键支持同名多版本
CREATE TABLE IF NOT EXISTS veclite_embedding_model (
    name            VARCHAR(128) NOT NULL,
    version         VARCHAR(64)  NOT NULL,
    provider        VARCHAR(64),                     -- http | openai | ollama | ollama-embed
    url             TEXT,
    api_key         TEXT,                            -- 以 Bearer 方式发送，可空
    dimension       INT          DEFAULT 0,          -- 0 = 由服务端决定
    timeout_millis  INT          DEFAULT 3000,
    batch_size      INT          DEFAULT 1,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at      TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (name, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_veclite_embedding_model_default
    ON veclite_embedding_model (is_default) WHERE is_default = TRUE;

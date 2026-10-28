CREATE TABLE model_configuration (
    id BIGSERIAL PRIMARY KEY,
    configuration_key VARCHAR(128) NOT NULL UNIQUE,
    model_kind VARCHAR(16) NOT NULL,
    provider VARCHAR(64),
    model VARCHAR(256),
    base_url TEXT,
    temperature DOUBLE PRECISION,
    dimension INTEGER,
    api_key_ciphertext BYTEA,
    api_key_iv BYTEA,
    encryption_key_id VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    activated_at TIMESTAMPTZ,
    failure_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_model_configuration_key
        CHECK (char_length(btrim(configuration_key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_model_configuration_kind
        CHECK (model_kind IN ('CHAT', 'EMBEDDING')),
    CONSTRAINT ck_model_configuration_status
        CHECK (status IN ('ACTIVE', 'PENDING', 'FAILED', 'RETIRED')),
    CONSTRAINT ck_model_configuration_provider
        CHECK (provider IS NULL OR char_length(btrim(provider)) BETWEEN 1 AND 64),
    CONSTRAINT ck_model_configuration_model
        CHECK (model IS NULL OR char_length(btrim(model)) BETWEEN 1 AND 256),
    CONSTRAINT ck_model_configuration_base_url
        CHECK (base_url IS NULL OR char_length(btrim(base_url)) BETWEEN 1 AND 2048),
    CONSTRAINT ck_model_configuration_temperature
        CHECK (temperature IS NULL OR (temperature >= 0 AND temperature <= 2)),
    CONSTRAINT ck_model_configuration_dimension
        CHECK (
            (model_kind = 'CHAT' AND dimension IS NULL)
            OR (model_kind = 'EMBEDDING' AND dimension = 1536)
        ),
    CONSTRAINT ck_model_configuration_secret
        CHECK (
            (
                api_key_ciphertext IS NULL
                AND api_key_iv IS NULL
                AND encryption_key_id IS NULL
            )
            OR (
                api_key_ciphertext IS NOT NULL
                AND octet_length(api_key_ciphertext) >= 16
                AND api_key_iv IS NOT NULL
                AND octet_length(api_key_iv) = 12
                AND encryption_key_id IS NOT NULL
                AND char_length(btrim(encryption_key_id)) BETWEEN 1 AND 128
            )
        ),
    CONSTRAINT ck_model_configuration_failure_reason
        CHECK (
            failure_reason IS NULL
            OR char_length(btrim(failure_reason)) BETWEEN 1 AND 1000
        )
);

CREATE UNIQUE INDEX ux_model_configuration_active
    ON model_configuration(model_kind) WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_model_configuration_pending
    ON model_configuration(model_kind) WHERE status = 'PENDING';

CREATE INDEX idx_model_configuration_status_created_at_id
    ON model_configuration(status, created_at DESC, id DESC);

CREATE INDEX idx_model_configuration_created_by
    ON model_configuration(created_by);

INSERT INTO model_configuration (
    configuration_key, model_kind, status, dimension, activated_at
)
VALUES
    ('bootstrap-chat', 'CHAT', 'ACTIVE', NULL, now()),
    ('bootstrap-embedding', 'EMBEDDING', 'ACTIVE', 1536, now());

CREATE FUNCTION active_embedding_config_id()
RETURNS BIGINT
LANGUAGE SQL
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT id
    FROM model_configuration
    WHERE model_kind = 'EMBEDDING' AND status = 'ACTIVE'
$$;

-- 替换所属表时保留全局身份序列。
ALTER SEQUENCE embedding_chunk_id_seq OWNED BY NONE;

CREATE TABLE embedding_chunk_v14 (
    id BIGINT NOT NULL DEFAULT nextval('embedding_chunk_id_seq'),
    embedding_config_id BIGINT NOT NULL DEFAULT active_embedding_config_id(),
    doc_ref_type VARCHAR(32) NOT NULL,
    doc_ref_id BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL DEFAULT 0,
    chunk_text TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_url TEXT,
    title VARCHAR(512),
    source_org VARCHAR(128),
    published_at TIMESTAMPTZ,
    degree VARCHAR(32),
    college VARCHAR(64),
    campus VARCHAR(64),
    grade_range VARCHAR(64),
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_embedding_chunk_v14
        PRIMARY KEY (embedding_config_id, id),
    CONSTRAINT uq_embedding_chunk_v14_identity
        UNIQUE (embedding_config_id, doc_ref_type, doc_ref_id, chunk_index),
    CONSTRAINT fk_embedding_chunk_v14_configuration
        FOREIGN KEY (embedding_config_id) REFERENCES model_configuration(id)
) PARTITION BY LIST (embedding_config_id);

DO $$
DECLARE
    bootstrap_id BIGINT;
BEGIN
    SELECT id INTO STRICT bootstrap_id
    FROM model_configuration
    WHERE configuration_key = 'bootstrap-embedding';

    EXECUTE format(
        'CREATE TABLE %I PARTITION OF embedding_chunk_v14 FOR VALUES IN (%s)',
        'embedding_chunk_cfg_' || bootstrap_id,
        bootstrap_id
    );
END
$$;

-- 构建 ANN 索引前先复制，再证明表替换没有丢失数据。
DO $$
DECLARE
    legacy_count BIGINT;
    migrated_count BIGINT;
    legacy_hash TEXT;
    migrated_hash TEXT;
BEGIN
    SELECT count(*), md5(COALESCE(string_agg(row_hash, '' ORDER BY id), ''))
    INTO legacy_count, legacy_hash
    FROM (
        SELECT id, md5(jsonb_build_array(
            id, doc_ref_type, doc_ref_id, chunk_index, chunk_text,
            embedding::text, source_type, source_url, title, source_org,
            published_at, degree, college, campus, grade_range, meta, created_at
        )::text) AS row_hash
        FROM embedding_chunk
    ) legacy_rows;

    INSERT INTO embedding_chunk_v14 (
        id, embedding_config_id, doc_ref_type, doc_ref_id, chunk_index,
        chunk_text, embedding, source_type, source_url, title, source_org,
        published_at, degree, college, campus, grade_range, meta, created_at
    )
    SELECT id,
           (SELECT id
            FROM model_configuration
            WHERE configuration_key = 'bootstrap-embedding'),
           doc_ref_type, doc_ref_id, chunk_index, chunk_text, embedding,
           source_type, source_url, title, source_org, published_at,
           degree, college, campus, grade_range, meta, created_at
    FROM embedding_chunk;

    SELECT count(*), md5(COALESCE(string_agg(row_hash, '' ORDER BY id), ''))
    INTO migrated_count, migrated_hash
    FROM (
        SELECT id, md5(jsonb_build_array(
            id, doc_ref_type, doc_ref_id, chunk_index, chunk_text,
            embedding::text, source_type, source_url, title, source_org,
            published_at, degree, college, campus, grade_range, meta, created_at
        )::text) AS row_hash
        FROM embedding_chunk_v14
    ) migrated_rows;

    IF legacy_count <> migrated_count OR legacy_hash IS DISTINCT FROM migrated_hash THEN
        RAISE EXCEPTION
            'Embedding V14 copy verification failed: source %, destination %',
            legacy_count, migrated_count
            USING ERRCODE = 'data_exception';
    END IF;
END
$$;

-- 显式旧 ID 可能已超过序列值；替换后绝不允许重用。
DO $$
DECLARE
    maximum_id BIGINT;
    sequence_value BIGINT;
    sequence_called BOOLEAN;
BEGIN
    SELECT max(id) INTO maximum_id FROM embedding_chunk_v14;
    SELECT last_value, is_called
    INTO sequence_value, sequence_called
    FROM embedding_chunk_id_seq;

    IF maximum_id IS NOT NULL
            AND (NOT sequence_called OR sequence_value < maximum_id) THEN
        PERFORM setval('embedding_chunk_id_seq', maximum_id, true);
    END IF;
END
$$;

CREATE INDEX idx_embedding_chunk_v14_doc_ref
    ON embedding_chunk_v14(doc_ref_type, doc_ref_id);
CREATE INDEX idx_embedding_chunk_v14_source_type
    ON embedding_chunk_v14(source_type);
CREATE INDEX idx_embedding_chunk_v14_degree
    ON embedding_chunk_v14(degree);
CREATE INDEX idx_embedding_chunk_v14_college
    ON embedding_chunk_v14(college);
CREATE INDEX idx_embedding_chunk_v14_campus
    ON embedding_chunk_v14(campus);
CREATE INDEX idx_embedding_chunk_v14_meta_gin
    ON embedding_chunk_v14 USING gin(meta);
CREATE INDEX idx_embedding_chunk_v14_hnsw
    ON embedding_chunk_v14 USING hnsw (embedding vector_cosine_ops);

-- 为引导子 ANN 索引设置稳定的诊断名称。
DO $$
DECLARE
    bootstrap_id BIGINT;
    child_index_name TEXT;
BEGIN
    SELECT id INTO STRICT bootstrap_id
    FROM model_configuration
    WHERE configuration_key = 'bootstrap-embedding';

    SELECT indexname INTO STRICT child_index_name
    FROM pg_indexes
    WHERE schemaname = current_schema()
      AND tablename = 'embedding_chunk_cfg_' || bootstrap_id
      AND indexdef ILIKE '% USING hnsw %';

    IF child_index_name <> 'idx_embedding_chunk_cfg_' || bootstrap_id || '_hnsw' THEN
        EXECUTE format(
            'ALTER INDEX %I RENAME TO %I',
            child_index_name,
            'idx_embedding_chunk_cfg_' || bootstrap_id || '_hnsw'
        );
    END IF;
END
$$;

ALTER TABLE embedding_chunk RENAME TO embedding_chunk_v13;
ALTER TABLE embedding_chunk_v14 RENAME TO embedding_chunk;
ALTER SEQUENCE embedding_chunk_id_seq OWNED BY embedding_chunk.id;
DROP TABLE embedding_chunk_v13;

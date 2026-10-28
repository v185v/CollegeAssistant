CREATE TABLE embedding_rebuild_manifest_item (
    job_id BIGINT NOT NULL REFERENCES admin_job(id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    content_version VARCHAR(128) NOT NULL,
    chunk_count INTEGER,
    chunk_text_checksum VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, target_type, target_id),
    CONSTRAINT ck_embedding_rebuild_manifest_item_target_type
        CHECK (target_type IN ('DOCUMENT', 'UGC')),
    CONSTRAINT ck_embedding_rebuild_manifest_item_target_id
        CHECK (target_id > 0),
    CONSTRAINT ck_embedding_rebuild_manifest_item_content_version
        CHECK (char_length(btrim(content_version)) BETWEEN 1 AND 128),
    CONSTRAINT ck_embedding_rebuild_manifest_item_completion
        CHECK (
            (chunk_count IS NULL AND chunk_text_checksum IS NULL)
            OR (
                chunk_count IS NOT NULL
                AND chunk_count >= 0
                AND chunk_text_checksum IS NOT NULL
                AND chunk_text_checksum ~ '^[0-9a-f]{64}$'
            )
        )
);

CREATE INDEX idx_embedding_rebuild_manifest_item_incomplete
    ON embedding_rebuild_manifest_item(job_id, target_type, target_id)
    WHERE chunk_count IS NULL;

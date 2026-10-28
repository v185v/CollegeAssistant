CREATE TABLE admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT,
    summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    remote_address INET,
    result VARCHAR(16) NOT NULL,
    error_code INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_audit_log_action
        CHECK (char_length(btrim(action)) BETWEEN 1 AND 64),
    CONSTRAINT ck_admin_audit_log_target_type
        CHECK (char_length(btrim(target_type)) BETWEEN 1 AND 32),
    CONSTRAINT ck_admin_audit_log_summary
        CHECK (jsonb_typeof(summary) = 'object'),
    CONSTRAINT ck_admin_audit_log_result
        CHECK (result IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_admin_audit_log_created_at_id
    ON admin_audit_log(created_at DESC, id DESC);
CREATE INDEX idx_admin_audit_log_actor_created_at_id
    ON admin_audit_log(actor_id, created_at DESC, id DESC)
    WHERE actor_id IS NOT NULL;
CREATE INDEX idx_admin_audit_log_target_created_at_id
    ON admin_audit_log(target_type, target_id, created_at DESC, id DESC)
    WHERE target_id IS NOT NULL;

ALTER TABLE ugc_report
    ADD COLUMN reviewer_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN resolved_at TIMESTAMPTZ;

CREATE INDEX idx_ugc_report_status_created_at_id
    ON ugc_report(status, created_at DESC, id DESC);

CREATE TABLE database_backup (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT REFERENCES admin_job(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    format VARCHAR(32) NOT NULL DEFAULT 'CUSTOM',
    storage_key VARCHAR(512),
    sha256 VARCHAR(64),
    size_bytes BIGINT,
    requested_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    retention_days INTEGER NOT NULL DEFAULT 7,
    expires_at TIMESTAMPTZ,
    error_summary VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_database_backup_status
        CHECK (status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED', 'VALIDATED', 'FAILED', 'DELETE_FAILED'
        )),
    CONSTRAINT ck_database_backup_format
        CHECK (char_length(btrim(format)) BETWEEN 1 AND 32),
    CONSTRAINT ck_database_backup_storage_key
        CHECK (storage_key IS NULL OR char_length(btrim(storage_key)) BETWEEN 1 AND 512),
    CONSTRAINT ck_database_backup_sha256
        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_database_backup_size_bytes
        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_database_backup_retention_days
        CHECK (retention_days BETWEEN 1 AND 3650)
);

CREATE UNIQUE INDEX ux_database_backup_active_singleton
    ON database_backup ((true))
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_database_backup_status_created_at_id
    ON database_backup(status, created_at DESC, id DESC);
CREATE INDEX idx_database_backup_expires_at_id
    ON database_backup(expires_at, id)
    WHERE expires_at IS NOT NULL;

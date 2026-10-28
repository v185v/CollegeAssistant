ALTER TABLE source ALTER COLUMN base_url DROP NOT NULL;

ALTER TABLE source
    ADD COLUMN connector_type VARCHAR(32) NOT NULL DEFAULT 'SINGLE_URL',
    ADD COLUMN deletion_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN schedule_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN schedule_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    ADD COLUMN cron_expression VARCHAR(128),
    ADD COLUMN same_host_only BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN max_depth INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN max_pages INTEGER NOT NULL DEFAULT 500,
    ADD COLUMN max_concurrency INTEGER NOT NULL DEFAULT 4,
    ADD COLUMN include_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN exclude_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN next_run_at TIMESTAMPTZ,
    ADD COLUMN last_run_at TIMESTAMPTZ,
    ADD COLUMN created_by BIGINT REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE source
    ADD CONSTRAINT ck_source_connector_type
        CHECK (connector_type IN (
            'LOCAL_UPLOAD', 'SINGLE_URL', 'SITE', 'RSS', 'SITEMAP'
        )),
    ADD CONSTRAINT ck_source_deletion_status
        CHECK (deletion_status IN ('NONE', 'DELETING', 'DELETE_FAILED')),
    ADD CONSTRAINT ck_source_schedule_type
        CHECK (schedule_type IN ('MANUAL', 'DAILY', 'WEEKLY', 'CRON')),
    ADD CONSTRAINT ck_source_connector_location
        CHECK (
            (connector_type = 'LOCAL_UPLOAD' AND base_url IS NULL)
            OR (
                connector_type IN ('SINGLE_URL', 'SITE', 'RSS', 'SITEMAP')
                AND base_url IS NOT NULL
                AND base_url ~* '^https?://[^[:space:]]+$'
            )
        ),
    ADD CONSTRAINT ck_source_schedule
        CHECK (
            (schedule_type = 'MANUAL' AND cron_expression IS NULL)
            OR (
                schedule_type IN ('DAILY', 'WEEKLY', 'CRON')
                AND cron_expression IS NOT NULL
                AND char_length(btrim(cron_expression)) BETWEEN 1 AND 128
            )
        ),
    ADD CONSTRAINT ck_source_schedule_zone
        CHECK (char_length(btrim(schedule_zone)) BETWEEN 1 AND 64),
    ADD CONSTRAINT ck_source_crawl_limits
        CHECK (
            max_depth BETWEEN 0 AND 32
            AND max_pages BETWEEN 1 AND 100000
            AND max_concurrency BETWEEN 1 AND 64
        ),
    ADD CONSTRAINT ck_source_include_patterns_array
        CHECK (jsonb_typeof(include_patterns) = 'array'),
    ADD CONSTRAINT ck_source_exclude_patterns_array
        CHECK (jsonb_typeof(exclude_patterns) = 'array');

CREATE INDEX idx_source_created_by ON source(created_by);
CREATE INDEX idx_source_schedule_due
    ON source(next_run_at, id)
    WHERE enabled = true
      AND deletion_status = 'NONE'
      AND schedule_type <> 'MANUAL';

ALTER TABLE document
    ADD COLUMN reference_type VARCHAR(32) NOT NULL DEFAULT 'EXTERNAL',
    ADD COLUMN external_source_url TEXT,
    ADD COLUMN internal_locator UUID,
    ADD COLUMN original_filename VARCHAR(512),
    ADD COLUMN normalized_filename VARCHAR(512),
    ADD COLUMN document_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN etag VARCHAR(512),
    ADD COLUMN http_last_modified TIMESTAMPTZ,
    ADD COLUMN last_seen_at TIMESTAMPTZ,
    ADD COLUMN last_checked_at TIMESTAMPTZ,
    ADD COLUMN degree VARCHAR(32),
    ADD COLUMN college VARCHAR(64),
    ADD COLUMN campus VARCHAR(64),
    ADD COLUMN grade_range VARCHAR(64);

UPDATE document
SET external_source_url = source_url,
    last_seen_at = ingested_at;

ALTER TABLE document
    ALTER COLUMN last_seen_at SET DEFAULT now(),
    ALTER COLUMN last_seen_at SET NOT NULL;

ALTER TABLE document
    ADD CONSTRAINT ck_document_reference_type
        CHECK (reference_type IN ('EXTERNAL', 'INTERNAL')),
    ADD CONSTRAINT ck_document_status
        CHECK (document_status IN (
            'PROCESSING', 'ACTIVE', 'FAILED', 'DELETING', 'DELETE_FAILED'
        )),
    ADD CONSTRAINT ck_document_reference_identity
        CHECK (
            (
                reference_type = 'EXTERNAL'
                AND internal_locator IS NULL
                AND external_source_url IS NOT NULL
                AND external_source_url ~* '^https?://[^[:space:]]+$'
                AND source_url = external_source_url
            )
            OR (
                reference_type = 'INTERNAL'
                AND internal_locator IS NOT NULL
                AND source_url = 'internal://document/' || internal_locator::text
                AND (
                    external_source_url IS NULL
                    OR external_source_url ~* '^https?://[^[:space:]]+$'
                )
            )
        );

CREATE FUNCTION default_external_document_reference()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reference_type = 'EXTERNAL' AND NEW.external_source_url IS NULL THEN
        NEW.external_source_url := NEW.source_url;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_document_external_reference_default
BEFORE INSERT OR UPDATE OF reference_type, source_url, external_source_url ON document
FOR EACH ROW EXECUTE FUNCTION default_external_document_reference();

CREATE UNIQUE INDEX ux_document_internal_locator
    ON document(internal_locator)
    WHERE internal_locator IS NOT NULL;

CREATE UNIQUE INDEX ux_document_internal_content_active
    ON document(source_id, content_hash)
    WHERE reference_type = 'INTERNAL'
      AND document_status NOT IN ('DELETING', 'DELETE_FAILED');

CREATE UNIQUE INDEX ux_document_internal_filename_active
    ON document(source_id, normalized_filename)
    WHERE reference_type = 'INTERNAL'
      AND normalized_filename IS NOT NULL
      AND document_status NOT IN ('DELETING', 'DELETE_FAILED');

CREATE INDEX idx_document_status_ingested_at_id
    ON document(document_status, ingested_at DESC, id DESC);
CREATE INDEX idx_document_external_last_checked
    ON document(last_checked_at, id)
    WHERE reference_type = 'EXTERNAL';

ALTER TABLE ingest_run
    ADD COLUMN trigger_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN scheduled_at TIMESTAMPTZ,
    ADD COLUMN discovered BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN worker_id VARCHAR(128),
    ADD COLUMN lease_token UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN error_code VARCHAR(64);

ALTER TABLE ingest_run
    ADD CONSTRAINT ck_ingest_run_trigger_type
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'IMPORT', 'RETRY')),
    ADD CONSTRAINT ck_ingest_run_status
        CHECK (status IN (
            'RUNNING', 'DEFERRED', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'
        )),
    ADD CONSTRAINT ck_ingest_run_non_negative_counts
        CHECK (
            discovered >= 0
            AND added >= 0
            AND updated >= 0
            AND skipped >= 0
            AND failed >= 0
        ),
    ADD CONSTRAINT ck_ingest_run_attempt
        CHECK (attempt >= 0),
    ADD CONSTRAINT ck_ingest_run_lease_identity
        CHECK ((worker_id IS NULL) = (lease_token IS NULL));

DROP INDEX ux_ingest_run_source_running;

CREATE UNIQUE INDEX ux_ingest_run_source_active
    ON ingest_run(source_id)
    WHERE status IN ('RUNNING', 'DEFERRED');

CREATE INDEX idx_ingest_run_active_heartbeat
    ON ingest_run(heartbeat_at, id)
    WHERE status IN ('RUNNING', 'DEFERRED');

CREATE TABLE ingest_run_item (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES ingest_run(id) ON DELETE CASCADE,
    item_key VARCHAR(512) NOT NULL,
    item_kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    document_id BIGINT REFERENCES document(id) ON DELETE SET NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    checkpoint_attempt INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, item_key),
    CONSTRAINT ck_ingest_run_item_key
        CHECK (char_length(btrim(item_key)) BETWEEN 1 AND 512),
    CONSTRAINT ck_ingest_run_item_kind
        CHECK (item_kind IN ('URL', 'FILE')),
    CONSTRAINT ck_ingest_run_item_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_ingest_run_item_retry
        CHECK (retry_count >= 0 AND checkpoint_attempt >= 0),
    CONSTRAINT ck_ingest_run_item_checkpoint
        CHECK (jsonb_typeof(checkpoint) = 'object')
);

CREATE INDEX idx_ingest_run_item_run_status_id
    ON ingest_run_item(run_id, status, id);
CREATE INDEX idx_ingest_run_item_document_id
    ON ingest_run_item(document_id);
CREATE INDEX idx_ingest_run_item_retry_due
    ON ingest_run_item(next_attempt_at, id)
    WHERE status = 'FAILED' AND next_attempt_at IS NOT NULL;

CREATE TABLE admin_job (
    id BIGSERIAL PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT,
    requested_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    worker_id VARCHAR(128),
    lease_token UUID,
    claimed_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_job_type
        CHECK (job_type IN (
            'SOURCE_DELETE', 'DOCUMENT_DELETE', 'DOCUMENT_REINDEX',
            'CONSISTENCY_SCAN', 'DATABASE_BACKUP', 'EMBEDDING_REBUILD',
            'INDEX_CLEANUP'
        )),
    CONSTRAINT ck_admin_job_status
        CHECK (status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL_SUCCESS',
            'FAILED', 'CANCELLED'
        )),
    CONSTRAINT ck_admin_job_target_type
        CHECK (char_length(btrim(target_type)) BETWEEN 1 AND 64),
    CONSTRAINT ck_admin_job_attempt
        CHECK (attempt >= 0),
    CONSTRAINT ck_admin_job_lease_identity
        CHECK ((worker_id IS NULL) = (lease_token IS NULL)),
    CONSTRAINT ck_admin_job_request_payload
        CHECK (jsonb_typeof(request_payload) = 'object')
);

CREATE UNIQUE INDEX ux_admin_job_active_target
    ON admin_job(job_type, target_type, target_id)
    WHERE target_id IS NOT NULL
      AND status IN ('QUEUED', 'RUNNING');

CREATE UNIQUE INDEX ux_admin_job_active_singleton
    ON admin_job(job_type)
    WHERE target_id IS NULL
      AND status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_admin_job_status_created_at_id
    ON admin_job(status, created_at, id);
CREATE INDEX idx_admin_job_requested_by
    ON admin_job(requested_by);
CREATE INDEX idx_admin_job_active_heartbeat
    ON admin_job(heartbeat_at, id)
    WHERE status = 'RUNNING';

CREATE TABLE admin_job_item (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES admin_job(id) ON DELETE CASCADE,
    item_key VARCHAR(512) NOT NULL,
    item_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    checkpoint_attempt INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, item_key),
    CONSTRAINT ck_admin_job_item_key
        CHECK (char_length(btrim(item_key)) BETWEEN 1 AND 512),
    CONSTRAINT ck_admin_job_item_type
        CHECK (char_length(btrim(item_type)) BETWEEN 1 AND 64),
    CONSTRAINT ck_admin_job_item_status
        CHECK (status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL_SUCCESS',
            'FAILED', 'CANCELLED'
        )),
    CONSTRAINT ck_admin_job_item_retry
        CHECK (retry_count >= 0 AND checkpoint_attempt >= 0),
    CONSTRAINT ck_admin_job_item_checkpoint
        CHECK (jsonb_typeof(checkpoint) = 'object')
);

CREATE INDEX idx_admin_job_item_job_status_id
    ON admin_job_item(job_id, status, id);

CREATE TABLE ingest_run (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES source(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    added BIGINT NOT NULL DEFAULT 0,
    updated BIGINT NOT NULL DEFAULT 0,
    skipped BIGINT NOT NULL DEFAULT 0,
    failed BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    error_detail TEXT,
    reason VARCHAR(200)
);

CREATE INDEX idx_ingest_run_source_id ON ingest_run(source_id);
CREATE INDEX idx_ingest_run_status ON ingest_run(status);
CREATE INDEX idx_ingest_run_started_at ON ingest_run(started_at DESC);
CREATE UNIQUE INDEX ux_ingest_run_source_running
    ON ingest_run(source_id)
    WHERE status = 'RUNNING';

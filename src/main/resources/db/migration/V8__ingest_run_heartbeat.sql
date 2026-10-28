ALTER TABLE ingest_run
    ADD COLUMN heartbeat_at TIMESTAMPTZ;

UPDATE ingest_run
SET heartbeat_at = started_at
WHERE heartbeat_at IS NULL;

ALTER TABLE ingest_run
    ALTER COLUMN heartbeat_at SET DEFAULT now(),
    ALTER COLUMN heartbeat_at SET NOT NULL;

CREATE INDEX idx_ingest_run_running_heartbeat
    ON ingest_run(source_id, heartbeat_at)
    WHERE status = 'RUNNING';

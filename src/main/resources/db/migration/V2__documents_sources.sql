CREATE TABLE source (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    base_url TEXT NOT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES source(id) ON DELETE CASCADE,
    title VARCHAR(512) NOT NULL,
    source_url TEXT NOT NULL,
    source_org VARCHAR(128),
    published_at TIMESTAMPTZ,
    content_hash VARCHAR(128) NOT NULL,
    file_object_key TEXT,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, source_url)
);

CREATE INDEX idx_source_enabled ON source(enabled);
CREATE INDEX idx_document_source_id ON document(source_id);
CREATE INDEX idx_document_content_hash ON document(content_hash);

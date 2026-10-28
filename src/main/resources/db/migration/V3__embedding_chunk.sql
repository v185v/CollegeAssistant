CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embedding_chunk (
    id BIGSERIAL PRIMARY KEY,
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
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_embedding_chunk_doc_ref ON embedding_chunk(doc_ref_type, doc_ref_id);
CREATE INDEX idx_embedding_chunk_source_type ON embedding_chunk(source_type);
CREATE INDEX idx_embedding_chunk_degree ON embedding_chunk(degree);
CREATE INDEX idx_embedding_chunk_college ON embedding_chunk(college);
CREATE INDEX idx_embedding_chunk_campus ON embedding_chunk(campus);
CREATE INDEX idx_embedding_chunk_meta_gin ON embedding_chunk USING gin(meta);
CREATE INDEX idx_embedding_chunk_embedding_hnsw
    ON embedding_chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE ugc_post (
    id BIGSERIAL PRIMARY KEY,
    author_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(80) NOT NULL,
    content TEXT NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    related_course VARCHAR(64),
    related_location VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    likes BIGINT NOT NULL DEFAULT 0,
    reports BIGINT NOT NULL DEFAULT 0,
    reviewed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    reject_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ugc_post_author_id ON ugc_post(author_id);
CREATE INDEX idx_ugc_post_status ON ugc_post(status);
CREATE INDEX idx_ugc_post_tags_gin ON ugc_post USING gin(tags);

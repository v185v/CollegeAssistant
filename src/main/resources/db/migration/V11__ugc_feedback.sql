CREATE TABLE ugc_like (
    post_id BIGINT NOT NULL REFERENCES ugc_post(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE ugc_report (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES ugc_post(id) ON DELETE CASCADE,
    reporter_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (post_id, reporter_id),
    CONSTRAINT ck_ugc_report_reason
        CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_ugc_report_status
        CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED'))
);

CREATE INDEX idx_ugc_like_user_id
    ON ugc_like(user_id);

CREATE INDEX idx_ugc_report_post_created_at_id
    ON ugc_report(post_id, created_at DESC, id DESC);

CREATE INDEX idx_ugc_report_reporter_id
    ON ugc_report(reporter_id);

CREATE FUNCTION sync_ugc_like_counter()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE ugc_post
        SET likes = likes + 1
        WHERE id = NEW.post_id;
        RETURN NEW;
    END IF;

    UPDATE ugc_post
    SET likes = GREATEST(likes - 1, 0)
    WHERE id = OLD.post_id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_ugc_like_counter
AFTER INSERT OR DELETE ON ugc_like
FOR EACH ROW EXECUTE FUNCTION sync_ugc_like_counter();

CREATE FUNCTION sync_ugc_report_counter()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE ugc_post
        SET reports = reports + 1
        WHERE id = NEW.post_id;
        RETURN NEW;
    END IF;

    UPDATE ugc_post
    SET reports = GREATEST(reports - 1, 0)
    WHERE id = OLD.post_id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_ugc_report_counter
AFTER INSERT OR DELETE ON ugc_report
FOR EACH ROW EXECUTE FUNCTION sync_ugc_report_counter();

UPDATE ugc_post
SET likes = 0,
    reports = 0;

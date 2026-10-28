package xyz.v185v.collegeassistant.ingestion.crawl;

import java.time.OffsetDateTime;
import java.util.Objects;

/** 表示有界 Retry-After 延迟，且不会阻塞工作线程。 */
public final class CrawlDeferredException extends RuntimeException {

    private final OffsetDateTime retryAt;

    public CrawlDeferredException(OffsetDateTime retryAt) {
        super("Remote source requested a deferred retry");
        this.retryAt = Objects.requireNonNull(retryAt, "retryAt");
    }

    public OffsetDateTime retryAt() {
        return retryAt;
    }
}

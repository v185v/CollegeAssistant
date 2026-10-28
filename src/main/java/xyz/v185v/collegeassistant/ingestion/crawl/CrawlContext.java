package xyz.v185v.collegeassistant.ingestion.crawl;

/** 每次运行的发现身份和不可变响应预算。 */
public record CrawlContext(Long runId, boolean preview, int maxUrls, long maxBytes) {

    public static final int PREVIEW_MAX_URLS = 20;
    public static final long PREVIEW_MAX_BYTES = 2L * 1024 * 1024;

    public CrawlContext {
        if (runId != null && runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        if (!preview && runId == null) {
// 兼容/直接调用特意不创建持久化运行。
        }
        if (maxUrls < 1 || maxUrls > 1000 || maxBytes < 1
                || maxBytes > (long) CrawledDocument.MAX_RAW_BYTES * 1000) {
            throw new IllegalArgumentException("Crawl budget is invalid");
        }
    }

    public static CrawlContext run(Long runId) {
        return new CrawlContext(runId, false, 1000,
                (long) CrawledDocument.MAX_RAW_BYTES * 1000);
    }

    public static CrawlContext direct() {
        return new CrawlContext(null, false, 1000,
                (long) CrawledDocument.MAX_RAW_BYTES * 1000);
    }

    public static CrawlContext previewContext() {
        return new CrawlContext(null, true, PREVIEW_MAX_URLS, PREVIEW_MAX_BYTES);
    }
}

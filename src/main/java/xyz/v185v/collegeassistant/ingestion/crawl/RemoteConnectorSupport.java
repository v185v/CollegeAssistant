package xyz.v185v.collegeassistant.ingestion.crawl;

import xyz.v185v.collegeassistant.ingestion.RemoteCrawlQueue;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.SourceRecord;

/** 远程发现连接器共用的有界过滤、robots 和队列行为。 */
final class RemoteConnectorSupport {

    static final String ROBOT_USER_AGENT = "CollegeAssistantBot";
    static final int ROBOTS_MAX_BYTES = 512 * 1024;

    private RemoteConnectorSupport() {
    }

    static RobotsPolicy loadRobots(
            SourceRecord source,
            URI base,
            CrawlContext context,
            HttpSourceCrawler http,
            RemoteCrawlQueue queue,
            Budget budget
    ) {
        URI canonicalBase = UrlCanonicalizer.canonicalize(base);
        String authority = canonicalBase.getRawAuthority();
        URI robots = URI.create(canonicalBase.getScheme() + "://" + authority + "/robots.txt");
        try {
            HttpSourceCrawler.FetchedResource response = http.fetchResource(
                    robots, RequestValidators.none(),
                    Math.max(1, Math.min(ROBOTS_MAX_BYTES, budget.remainingBytes())));
            if (response.notModified() || !budget.consumeBytes(response.rawBytes().length)) {
                return RobotsPolicy.allowAll();
            }
            return RobotsPolicy.parse(
                    new String(response.rawBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    ROBOT_USER_AGENT);
        } catch (CrawlDeferredException exception) {
            queue.deferUnfinished(context, exception.retryAt());
            throw exception;
        } catch (IOException exception) {
            return RobotsPolicy.allowAll();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(exception);
        }
    }

    static CrawledDocument fetchDocument(
            SourceRecord source,
            URI uri,
            CrawlContext context,
            boolean force,
            HttpSourceCrawler http,
            RemoteCrawlQueue queue
    ) {
        queue.persist(context, uri);
        try {
            return http.crawlUrl(source, uri, context, force).getFirst();
        } catch (CrawlDeferredException exception) {
            queue.deferUnfinished(context, exception.retryAt());
            throw exception;
        }
    }

    static boolean allowedBySource(SourceRecord source, URI base, URI candidate) {
        if (source.sameHostOnly() && !UrlCanonicalizer.sameOrigin(base, candidate)) {
            return false;
        }
        String path = UrlCanonicalizer.normalizedPath(candidate);
        if (matchesAny(source.excludePatterns(), path)) {
            return false;
        }
        return true;
    }

    static boolean includedAsDocument(SourceRecord source, URI candidate) {
        String path = UrlCanonicalizer.normalizedPath(candidate);
        return source.includePatterns().isEmpty() || matchesAny(source.includePatterns(), path);
    }

    private static boolean matchesAny(List<String> expressions, String path) {
        return expressions.stream().map(PathGlob::new).anyMatch(glob -> glob.matches(path));
    }

    static BusinessException failure(Throwable cause) {
        return new BusinessException(ErrorCode.INGEST_FAILED, "Source crawl failed", cause);
    }

    static final class Budget {

        private final int maxUrls;
        private final long maxBytes;
        private int fetchedUrls;
        private long bytes;

        Budget(SourceRecord source, CrawlContext context) {
            CrawlContext effective = context == null ? CrawlContext.direct() : context;
            this.maxUrls = Math.min(source.maxPages(), effective.maxUrls());
            this.maxBytes = effective.maxBytes();
        }

        boolean canFetch() {
            return fetchedUrls < maxUrls && bytes < maxBytes;
        }

        void fetched() {
            fetchedUrls++;
        }

        int fetchedUrls() {
            return fetchedUrls;
        }

        int maxUrls() {
            return maxUrls;
        }

        int remainingBytes() {
            return (int) Math.min(CrawledDocument.MAX_RAW_BYTES, Math.max(0, maxBytes - bytes));
        }

        boolean consumeBytes(long amount) {
            if (amount < 0 || bytes + amount > maxBytes) {
                return false;
            }
            bytes += amount;
            return true;
        }
    }
}

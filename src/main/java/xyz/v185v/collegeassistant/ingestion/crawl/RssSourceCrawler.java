package xyz.v185v.collegeassistant.ingestion.crawl;

import xyz.v185v.collegeassistant.ingestion.RemoteCrawlQueue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.document.SourceRecord;

/** XXE 安全的 RSS/Atom 发现，其条目使用共用的受保护文档抓取流程。 */
@Component
public class RssSourceCrawler implements SourceCrawler {

    private static final int MAX_FEED_BYTES = 10 * 1024 * 1024;

    private final HttpSourceCrawler http;
    private final RemoteCrawlQueue queue;

    public RssSourceCrawler(HttpSourceCrawler http, RemoteCrawlQueue queue) {
        this.http = http;
        this.queue = queue;
    }

    @Override
    public List<CrawledDocument> crawl(SourceRecord source, boolean force) {
        return crawl(source, CrawlContext.direct(), force);
    }

    @Override
    public List<CrawledDocument> crawl(
            SourceRecord source,
            CrawlContext context,
            boolean force
    ) {
        URI base = UrlCanonicalizer.canonicalize(source.baseUrl());
        RemoteConnectorSupport.Budget budget = new RemoteConnectorSupport.Budget(source, context);
        RobotsPolicy robots = RemoteConnectorSupport.loadRobots(
                source, base, context, http, queue, budget);
        if (!robots.allows(base) || budget.remainingBytes() == 0) {
            return List.of();
        }

        HttpSourceCrawler.FetchedResource feed;
        try {
            feed = http.fetchResource(base, RequestValidators.none(),
                    Math.max(1, Math.min(MAX_FEED_BYTES, budget.remainingBytes())));
        } catch (CrawlDeferredException exception) {
            queue.deferUnfinished(context, exception.retryAt());
            throw exception;
        } catch (IOException exception) {
            throw RemoteConnectorSupport.failure(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw RemoteConnectorSupport.failure(exception);
        }
        if (feed.notModified() || !budget.consumeBytes(feed.rawBytes().length)) {
            return List.of();
        }

        Set<String> links = new LinkedHashSet<>(RemoteXml.parse(feed.rawBytes()).rssLinks());
        List<CrawledDocument> documents = new ArrayList<>();
        for (String link : links) {
            if (!budget.canFetch()) {
                break;
            }
            try {
                URI candidate = UrlCanonicalizer.canonicalize(base.resolve(link));
                if (!RemoteConnectorSupport.allowedBySource(source, base, candidate)
                        || !RemoteConnectorSupport.includedAsDocument(source, candidate)
                        || !robots.allows(candidate)) {
                    continue;
                }
                http.validateTarget(candidate);
                CrawledDocument document = RemoteConnectorSupport.fetchDocument(
                        source, candidate, context, force, http, queue);
                budget.fetched();
                if (!budget.consumeBytes(document.rawBytes().length)) {
                    break;
                }
                documents.add(document);
            } catch (CrawlDeferredException exception) {
                throw exception;
            } catch (BusinessException | IOException ignored) {
// 单个格式错误或失败的 feed 条目不会影响其余条目。
            }
        }
        return List.copyOf(documents);
    }
}

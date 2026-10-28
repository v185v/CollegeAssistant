package xyz.v185v.collegeassistant.ingestion.crawl;

import xyz.v185v.collegeassistant.ingestion.RemoteCrawlQueue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.document.SourceRecord;

/** 带 XXE 防护和严格解压上限的嵌套 sitemap 与 gzip 发现。 */
@Component
public class SitemapSourceCrawler implements SourceCrawler {

    static final int MAX_SITEMAP_BYTES = 10 * 1024 * 1024;
    static final int MAX_SITEMAP_INDEXES = 20;

    private final HttpSourceCrawler http;
    private final RemoteCrawlQueue queue;

    public SitemapSourceCrawler(HttpSourceCrawler http, RemoteCrawlQueue queue) {
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
        ArrayDeque<MapNode> maps = new ArrayDeque<>();
        Set<String> visitedMaps = new HashSet<>();
        maps.add(new MapNode(base, 0));
        List<URI> discovered = new ArrayList<>();
        int indexes = 0;

        while (!maps.isEmpty() && indexes < MAX_SITEMAP_INDEXES && budget.remainingBytes() > 0) {
            MapNode map = maps.removeFirst();
            String key = map.uri().toASCIIString();
            if (!visitedMaps.add(key) || map.depth() > MAX_SITEMAP_INDEXES
                    || !RemoteConnectorSupport.allowedBySource(source, base, map.uri())) {
                continue;
            }
            HttpSourceCrawler.FetchedResource resource;
            try {
                http.validateTarget(map.uri());
                resource = http.fetchResource(map.uri(), RequestValidators.none(),
                        Math.max(1, Math.min(MAX_SITEMAP_BYTES, budget.remainingBytes())));
            } catch (CrawlDeferredException exception) {
                queue.deferUnfinished(context, exception.retryAt());
                throw exception;
            } catch (IOException exception) {
                continue;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw RemoteConnectorSupport.failure(exception);
            }
            if (resource.notModified() || !budget.consumeBytes(resource.rawBytes().length)) {
                continue;
            }
            byte[] xml = decode(resource);
            RemoteXml.ParsedXml parsed = RemoteXml.parse(xml);
            if (parsed.rootName().equalsIgnoreCase("sitemapindex")) {
                indexes++;
                for (String location : parsed.locations()) {
                    try {
                        URI nested = UrlCanonicalizer.canonicalize(map.uri().resolve(location));
                        if (RemoteConnectorSupport.allowedBySource(source, base, nested)) {
                            http.validateTarget(nested);
                            maps.addLast(new MapNode(nested, map.depth() + 1));
                        }
                    } catch (Exception ignored) {
// 无效或禁止的嵌套索引在抓取前忽略。
                    }
                }
            } else if (parsed.rootName().equalsIgnoreCase("urlset")) {
                for (String location : parsed.locations()) {
                    try {
                        URI candidate = UrlCanonicalizer.canonicalize(map.uri().resolve(location));
                        if (RemoteConnectorSupport.allowedBySource(source, base, candidate)
                                && RemoteConnectorSupport.includedAsDocument(source, candidate)
                                && robots.allows(candidate)) {
                            http.validateTarget(candidate);
                            discovered.add(candidate);
                        }
                    } catch (Exception ignored) {
// 无效或禁止的 sitemap 条目不会进入持久化队列。
                    }
                }
            }
        }

        List<CrawledDocument> documents = new ArrayList<>();
        for (URI candidate : new java.util.LinkedHashSet<>(discovered)) {
            if (!budget.canFetch()) {
                break;
            }
            try {
                CrawledDocument document = RemoteConnectorSupport.fetchDocument(
                        source, candidate, context, force, http, queue);
                budget.fetched();
                if (!budget.consumeBytes(document.rawBytes().length)) {
                    break;
                }
                documents.add(document);
            } catch (CrawlDeferredException exception) {
                throw exception;
            } catch (BusinessException ignored) {
// 继续处理彼此独立的 sitemap 条目。
            }
        }
        return List.copyOf(documents);
    }

    private byte[] decode(HttpSourceCrawler.FetchedResource resource) {
        boolean gzip = resource.uri().getPath().toLowerCase(Locale.ROOT).endsWith(".gz")
                || resource.contentEncoding() != null
                && resource.contentEncoding().toLowerCase(Locale.ROOT).contains("gzip");
        if (!gzip) {
            if (resource.rawBytes().length > MAX_SITEMAP_BYTES) {
                throw RemoteConnectorSupport.failure(new IOException("Sitemap exceeds limit"));
            }
            return resource.rawBytes();
        }
        try (GZIPInputStream input = new GZIPInputStream(
                new ByteArrayInputStream(resource.rawBytes()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int read = input.read(buffer, 0,
                        Math.min(buffer.length, MAX_SITEMAP_BYTES - total + 1));
                if (read < 0) {
                    return output.toByteArray();
                }
                total += read;
                if (total > MAX_SITEMAP_BYTES) {
                    throw new IOException("Decompressed sitemap exceeds limit");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw RemoteConnectorSupport.failure(exception);
        }
    }

    private record MapNode(URI uri, int depth) {
    }
}

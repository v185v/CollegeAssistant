package xyz.v185v.collegeassistant.ingestion.crawl;

import xyz.v185v.collegeassistant.ingestion.RemoteCrawlQueue;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.sax.LinkContentHandler;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.document.SourceRecord;

/** 同源、广度优先的 SITE 发现，带路径过滤和 robots 约束。 */
@Component
public class SiteSourceCrawler implements SourceCrawler {

    private final HttpSourceCrawler http;
    private final RemoteCrawlQueue queue;

    public SiteSourceCrawler(HttpSourceCrawler http, RemoteCrawlQueue queue) {
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
        ArrayDeque<Node> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        List<CrawledDocument> documents = new ArrayList<>();
        offer(base, 0, pending, seen);

        while (!pending.isEmpty() && budget.canFetch()) {
            Node node = pending.removeFirst();
            if (!RemoteConnectorSupport.allowedBySource(source, base, node.uri())
                    || !robots.allows(node.uri())) {
                continue;
            }
            CrawledDocument document;
            try {
                document = RemoteConnectorSupport.fetchDocument(
                        source, node.uri(), context, force, http, queue);
            } catch (CrawlDeferredException exception) {
                throw exception;
            } catch (BusinessException exception) {
                continue;
            }
            budget.fetched();
            if (!budget.consumeBytes(document.rawBytes().length)) {
                break;
            }
            if (RemoteConnectorSupport.includedAsDocument(source, node.uri())) {
                documents.add(document);
            }
            if (document.notModified() || node.depth() >= source.maxDepth()
                    || !isHtml(document)) {
                continue;
            }
            for (String link : discoverLinks(document)) {
                try {
                    URI candidate = UrlCanonicalizer.canonicalize(node.uri().resolve(link));
                    if (!RemoteConnectorSupport.allowedBySource(source, base, candidate)) {
                        continue;
                    }
                    http.validateTarget(candidate);
                    offer(candidate, node.depth() + 1, pending, seen);
                } catch (Exception ignored) {
// 无效或禁止的发现 URL 不会进入持久化队列。
                }
            }
        }
        return List.copyOf(documents);
    }

    private void offer(URI uri, int depth, ArrayDeque<Node> pending, Set<String> seen) {
        URI canonical = UrlCanonicalizer.canonicalize(uri);
        if (seen.add(canonical.toASCIIString())) {
            pending.addLast(new Node(canonical, depth));
        }
    }

    private boolean isHtml(CrawledDocument document) {
        String type = document.contentType().toLowerCase(java.util.Locale.ROOT);
        return type.contains("text/html") || document.filename().toLowerCase(java.util.Locale.ROOT)
                .matches(".*\\.html?$");
    }

    private List<String> discoverLinks(CrawledDocument document) {
        LinkContentHandler links = new LinkContentHandler();
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, document.sourceUrl());
        metadata.set(HttpHeaders.CONTENT_TYPE, document.contentType());
        try (TikaInputStream input = TikaInputStream.get(document.rawBytes(), metadata)) {
            new JSoupParser().parse(
                    input, links, metadata, new ParseContext());
            return links.getLinks().stream()
                    .filter(link -> link.isAnchor() || link.isLink() || link.isIframe())
                    .map(org.apache.tika.sax.Link::getUri)
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        } catch (Exception ignored) {
// 即使链接发现无法解析，已抓取页面仍可摄取。
            return List.of();
        }
    }

    private record Node(URI uri, int depth) {
    }
}

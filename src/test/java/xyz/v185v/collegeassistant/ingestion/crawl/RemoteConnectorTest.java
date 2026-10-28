package xyz.v185v.collegeassistant.ingestion.crawl;

import xyz.v185v.collegeassistant.ingestion.*;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.SourceRecord;

class RemoteConnectorTest {

    private static final OffsetDateTime HTTP_DATE =
            OffsetDateTime.parse("2026-07-25T04:00:00Z");

    @Test
    void canonicalizesUrlsAndComparesNormalizedOrigins() {
        URI canonical = UrlCanonicalizer.canonicalize(
                URI.create("HTTPS://Example.EDU:443/a/../docs/%7euser?q=1#fragment"));

        assertThat(canonical.toASCIIString())
                .isEqualTo("https://example.edu/docs/~user?q=1");
        assertThat(UrlCanonicalizer.sameOrigin(
                canonical, URI.create("https://EXAMPLE.edu:443/other"))).isTrue();
        assertThat(UrlCanonicalizer.sameOrigin(
                canonical, URI.create("https://example.edu:444/other"))).isFalse();
        assertThatThrownBy(() -> UrlCanonicalizer.canonicalize(
                URI.create("https://user:secret@example.edu/private")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathGlobsMatchOnlyNormalizedPaths() {
        PathGlob documents = new PathGlob("/docs/**/20??/*.pdf");

        assertThat(documents.matches(URI.create(
                "https://example.edu/docs/a/../rules/2026/guide.pdf?download=true"))).isTrue();
        assertThat(documents.matches(URI.create(
                "https://example.edu/docs/rules/2026/nested/guide.pdf"))).isFalse();
        assertThat(new PathGlob("/news/*.html").matches("/news/one.html")).isTrue();
        assertThat(new PathGlob("/news/*.html").matches("/news/archive/one.html")).isFalse();
    }

    @Test
    void robotsUsesTheMostSpecificRuleAndDefaultsToAllow() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /private/
                Allow: /private/public/

                User-agent: CollegeAssistantBot
                Disallow: /bot-only/
                """, "CollegeAssistantBot");

        assertThat(policy.allows(URI.create("https://example.edu/private/secret"))).isFalse();
        assertThat(policy.allows(URI.create("https://example.edu/private/public/rules"))).isTrue();
        assertThat(policy.allows(URI.create("https://example.edu/bot-only/page"))).isFalse();
        assertThat(policy.allows(URI.create("https://example.edu/open"))).isTrue();
    }

    @Test
    void privateOriginsRequireAnExactDeploymentAllowlistEntry() throws Exception {
        SourceNetworkPolicyProperties properties = networkProperties(
                List.of("http://LOCALHOST:8080"));
        HostAddressResolver loopback = host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        };
        HostAddressPolicy policy = HostAddressPolicy.deployment(properties, loopback);

        policy.validate(URI.create("http://localhost:8080/document"));
        assertThatThrownBy(() -> policy.validate(
                URI.create("http://localhost:8081/document")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> policy.validate(
                URI.create("https://localhost:8080/document")))
                .isInstanceOf(IOException.class);

        SourceNetworkPolicyProperties wildcard = networkProperties(
                List.of("https://*.example.edu"));
        assertThatThrownBy(wildcard::allowedPrivateOrigins)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conditionalFetchSendsOnlyStoredValidatorsAnd304DoesNotOpenBody() {
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceRecord source = source(SourceConnectorType.SINGLE_URL,
                "https://example.edu/rules.html", List.of(), List.of());
        DocumentRecord existing = new DocumentRecord(
                9L, source.id(), "Rules", source.baseUrl(), source.name(), HTTP_DATE,
                "a".repeat(64), "objects/rules", HTTP_DATE);
        existing = new DocumentRecord(
                existing.id(), existing.sourceId(), existing.title(), existing.sourceUrl(),
                existing.sourceOrg(), existing.publishedAt(), existing.contentHash(),
                existing.fileObjectKey(), existing.ingestedAt(), existing.referenceType(),
                existing.externalSourceUrl(), existing.internalLocator(), existing.originalFilename(),
                existing.normalizedFilename(), existing.documentStatus(), "\"abc\"", HTTP_DATE,
                HTTP_DATE, HTTP_DATE, existing.degree(), existing.college(), existing.campus(),
                existing.gradeRange());
        when(documents.findBySourceIdAndSourceUrl(source.id(), source.baseUrl()))
                .thenReturn(Optional.of(existing));

        AtomicReference<RequestValidators> validators = new AtomicReference<>();
        TrackingResponse response = new TrackingResponse(304, Map.of(),
                new ByteArrayInputStream("must-not-be-read".getBytes(StandardCharsets.UTF_8)));
        SourceHttpTransport transport = new SourceHttpTransport() {
            @Override
            public Response get(URI uri, Duration timeout) {
                throw new AssertionError("validator-aware overload must be used");
            }

            @Override
            public Response get(URI uri, Duration timeout, RequestValidators requestValidators) {
                validators.set(requestValidators);
                return response;
            }
        };

        HttpSourceCrawler crawler = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, uri -> { }, documents,
                networkProperties(List.of()));
        CrawledDocument result = crawler.crawl(
                source, CrawlContext.run(77L), false).getFirst();

        assertThat(validators.get().etag()).isEqualTo("\"abc\"");
        assertThat(validators.get().lastModified()).isEqualTo(HTTP_DATE);
        assertThat(result.notModified()).isTrue();
        assertThat(response.bodyOpened()).isFalse();
        assertThat(response.closed()).isTrue();
    }

    @Test
    void forceOmitsConditionalValidators() {
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceRecord source = source(SourceConnectorType.SINGLE_URL,
                "https://example.edu/rules.html", List.of(), List.of());
        when(documents.findBySourceIdAndSourceUrl(source.id(), source.baseUrl()))
                .thenReturn(Optional.of(new DocumentRecord(
                        9L, source.id(), "Rules", source.baseUrl(), source.name(), HTTP_DATE,
                        "a".repeat(64), "objects/rules", HTTP_DATE)));
        AtomicReference<RequestValidators> validators = new AtomicReference<>();
        SourceHttpTransport transport = new SourceHttpTransport() {
            @Override
            public Response get(URI uri, Duration timeout) {
                throw new AssertionError("validator-aware overload must be used");
            }

            @Override
            public Response get(URI uri, Duration timeout, RequestValidators requestValidators) {
                validators.set(requestValidators);
                return new TrackingResponse(200, Map.of("Content-Type", "text/plain"),
                        new ByteArrayInputStream("same".getBytes(StandardCharsets.UTF_8)));
            }
        };

        HttpSourceCrawler crawler = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, uri -> { }, documents,
                networkProperties(List.of()));
        crawler.crawl(source, CrawlContext.run(77L), true);

        assertThat(validators.get()).isEqualTo(RequestValidators.none());
    }

    @Test
    void rateLimitCapsRetryAfterAndDefersEveryDurableUnfinishedItem() {
        IngestRunItemRepository items = mock(IngestRunItemRepository.class);
        when(items.upsertDiscovered(77L, "https://example.edu/rules.html"))
                .thenReturn(Optional.of(mock(IngestRunItemDto.class)));
        RemoteCrawlQueue queue = new RemoteCrawlQueue(items);
        SourceNetworkPolicyProperties properties = networkProperties(List.of());
        SourceHttpTransport transport = new SourceHttpTransport() {
            @Override
            public Response get(URI uri, Duration timeout) {
                return get(uri, timeout, RequestValidators.none());
            }

            @Override
            public Response get(URI uri, Duration timeout, RequestValidators validators) {
                return new TrackingResponse(429, Map.of("Retry-After", "3600"),
                        new ByteArrayInputStream(new byte[0]));
            }
        };
        HttpSourceCrawler http = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, uri -> { },
                mock(DocumentRepository.class), properties, queue);
        SourceRecord source = source(SourceConnectorType.SINGLE_URL,
                "https://example.edu/rules.html", List.of(), List.of());
        OffsetDateTime before = OffsetDateTime.now().plusMinutes(5).minusSeconds(1);

        assertThatThrownBy(() -> http.crawl(source, CrawlContext.run(77L), false))
                .isInstanceOfSatisfying(CrawlDeferredException.class, exception ->
                        assertThat(exception.retryAt())
                                .isBetween(before, before.plusSeconds(2)));

        verify(items).upsertDiscovered(77L, source.baseUrl());
        verify(items).deferUnfinished(eq(77L), any(OffsetDateTime.class));
    }

    @Test
    void routerSelectsEveryRemoteConnectorAndRejectsLocalUpload() {
        HttpSourceCrawler single = mock(HttpSourceCrawler.class);
        SiteSourceCrawler site = mock(SiteSourceCrawler.class);
        RssSourceCrawler rss = mock(RssSourceCrawler.class);
        SitemapSourceCrawler sitemap = mock(SitemapSourceCrawler.class);
        SourceCrawlerRouter router = new SourceCrawlerRouter(single, site, rss, sitemap);

        assertThat(router.route(SourceConnectorType.SINGLE_URL)).isSameAs(single);
        assertThat(router.route(SourceConnectorType.SITE)).isSameAs(site);
        assertThat(router.route(SourceConnectorType.RSS)).isSameAs(rss);
        assertThat(router.route(SourceConnectorType.SITEMAP)).isSameAs(sitemap);
        assertThatThrownBy(() -> router.route(SourceConnectorType.LOCAL_UPLOAD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discoversBoundedSiteRssAndNestedGzipSitemapGraphs() throws Exception {
        AtomicInteger blockedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> respond(exchange, 200, "text/plain", """
                User-agent: *
                Disallow: /blocked/
                """.getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/site/index.html", exchange -> respond(
                exchange, 200, "text/html", """
                        <html><body>
                        <a href="/site/a.html#fragment">A</a>
                        <a href="/blocked/secret.html">blocked</a>
                        </body></html>
                        """.getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/site/a.html", exchange -> respond(
                exchange, 200, "text/html",
                "<a href='/site/deep/b.html'>B</a>".getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/site/deep/b.html", exchange -> respond(
                exchange, 200, "text/html", "done".getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/blocked/secret.html", exchange -> {
            blockedRequests.incrementAndGet();
            respond(exchange, 200, "text/html", "secret".getBytes(StandardCharsets.UTF_8), Map.of());
        });
        server.createContext("/feed.xml", exchange -> respond(
                exchange, 200, "application/rss+xml", """
                        <?xml version="1.0"?>
                        <rss version="2.0"><channel>
                          <item><link>/rss/one.html</link></item>
                          <item><link>/rss/two.html</link></item>
                        </channel></rss>
                        """.getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/rss/one.html", exchange -> respond(
                exchange, 200, "text/html", "one".getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/rss/two.html", exchange -> respond(
                exchange, 200, "text/html", "two".getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/maps/root.xml", exchange -> respond(
                exchange, 200, "application/xml", """
                        <?xml version="1.0"?>
                        <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                          <sitemap><loc>/maps/nested.xml.gz</loc></sitemap>
                        </sitemapindex>
                        """.getBytes(StandardCharsets.UTF_8), Map.of()));
        byte[] nested = gzip("""
                <?xml version="1.0"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>/docs/one.html</loc></url>
                  <url><loc>/docs/two.html</loc></url>
                </urlset>
                """.getBytes(StandardCharsets.UTF_8));
        server.createContext("/maps/nested.xml.gz", exchange -> respond(
                exchange, 200, "application/gzip", nested, Map.of("Content-Encoding", "gzip")));
        server.createContext("/docs/one.html", exchange -> respond(
                exchange, 200, "text/html", "doc one".getBytes(StandardCharsets.UTF_8), Map.of()));
        server.createContext("/docs/two.html", exchange -> respond(
                exchange, 200, "text/html", "doc two".getBytes(StandardCharsets.UTF_8), Map.of()));
        server.start();

        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectorFixture fixture = connectorFixture(origin);

            List<CrawledDocument> site = fixture.site().crawl(
                    source(SourceConnectorType.SITE, origin + "/site/index.html",
                            List.of("/site/**"), List.of()),
                    CrawlContext.previewContext(), false);
            List<CrawledDocument> rss = fixture.rss().crawl(
                    source(SourceConnectorType.RSS, origin + "/feed.xml", List.of(), List.of()),
                    CrawlContext.previewContext(), false);
            List<CrawledDocument> sitemap = fixture.sitemap().crawl(
                    source(SourceConnectorType.SITEMAP, origin + "/maps/root.xml", List.of(), List.of()),
                    CrawlContext.previewContext(), false);

            assertThat(site).extracting(CrawledDocument::sourceUrl)
                    .containsExactly(
                            origin + "/site/index.html",
                            origin + "/site/a.html",
                            origin + "/site/deep/b.html");
            assertThat(rss).extracting(CrawledDocument::sourceUrl)
                    .containsExactly(origin + "/rss/one.html", origin + "/rss/two.html");
            assertThat(sitemap).extracting(CrawledDocument::sourceUrl)
                    .containsExactly(origin + "/docs/one.html", origin + "/docs/two.html");
            assertThat(blockedRequests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void everyRedirectIsRevalidatedAgainstItsOwnPrivateOrigin() throws Exception {
        AtomicInteger forbiddenTargetRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> respond(
                exchange, 302, "text/plain", new byte[0], Map.of(
                        "Location", "http://localhost:" + server.getAddress().getPort() + "/forbidden")));
        server.createContext("/forbidden", exchange -> {
            forbiddenTargetRequests.incrementAndGet();
            respond(exchange, 200, "text/plain", "no".getBytes(StandardCharsets.UTF_8), Map.of());
        });
        server.start();

        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectorFixture fixture = connectorFixture(origin);
            SourceRecord source = source(
                    SourceConnectorType.SINGLE_URL, origin + "/redirect", List.of(), List.of());

            assertThatThrownBy(() -> fixture.http().crawl(
                    source, CrawlContext.previewContext(), false))
                    .isInstanceOf(xyz.v185v.collegeassistant.common.BusinessException.class)
                    .hasMessage("Source crawl failed");
            assertThat(forbiddenTargetRequests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private ConnectorFixture connectorFixture(String allowedOrigin) {
        SourceNetworkPolicyProperties properties = networkProperties(List.of(allowedOrigin));
        HostAddressResolver resolver = InetAddress::getAllByName;
        HttpSourceCrawler http = new HttpSourceCrawler(
                new ApacheSourceHttpTransport(resolver, properties),
                Duration.ofSeconds(3), CrawledDocument.MAX_RAW_BYTES,
                HostAddressPolicy.deployment(properties, resolver),
                mock(DocumentRepository.class), properties);
        RemoteCrawlQueue queue = new RemoteCrawlQueue(mock(IngestRunItemRepository.class));
        return new ConnectorFixture(
                http,
                new SiteSourceCrawler(http, queue),
                new RssSourceCrawler(http, queue),
                new SitemapSourceCrawler(http, queue));
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body,
            Map<String, String> headers
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] gzip(byte[] source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(source);
        }
        return output.toByteArray();
    }

    private SourceNetworkPolicyProperties networkProperties(List<String> allowlist) {
        SourceNetworkPolicyProperties properties = new SourceNetworkPolicyProperties();
        properties.setPrivateOriginAllowlist(allowlist);
        properties.setSameOriginDelay(Duration.ZERO);
        properties.setMaxRetryAfter(Duration.ofMinutes(5));
        return properties;
    }

    private SourceRecord source(
            SourceConnectorType connectorType,
            String baseUrl,
            List<String> includes,
            List<String> excludes
    ) {
        return new SourceRecord(
                1L, "Remote", baseUrl, "OFFICIAL", true, connectorType, "NONE",
                SourceScheduleType.MANUAL, "Asia/Shanghai", null, true,
                3, 20, 2, includes, excludes, null, null, 1L, HTTP_DATE, HTTP_DATE);
    }

    private static final class TrackingResponse implements SourceHttpTransport.Response {

        private final int status;
        private final Map<String, String> headers;
        private final InputStream body;
        private boolean bodyOpened;
        private boolean closed;

        private TrackingResponse(int status, Map<String, String> headers, InputStream body) {
            this.status = status;
            this.headers = Map.copyOf(headers);
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public Optional<String> firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst();
        }

        @Override
        public InputStream body() {
            bodyOpened = true;
            return body;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            body.close();
        }

        boolean bodyOpened() {
            return bodyOpened;
        }

        boolean closed() {
            return closed;
        }
    }

    private record ConnectorFixture(
            HttpSourceCrawler http,
            SiteSourceCrawler site,
            RssSourceCrawler rss,
            SitemapSourceCrawler sitemap
    ) {
    }
}

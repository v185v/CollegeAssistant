package xyz.v185v.collegeassistant.ingestion.crawl;

import xyz.v185v.collegeassistant.ingestion.*;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.SourceRecord;

class HttpSourceCrawlerTest {

    private static final String FAILURE_MESSAGE = "Source crawl failed";
    private static final HostAddressPolicy TEST_TRUSTED = host -> {
    };

    @Test
    void apacheTransportKeepsOriginalUriAndDisablesRedirectsAndProxy() throws Exception {
        Duration requestTimeout = Duration.ofSeconds(30);
        var connectionConfig = ApacheSourceHttpTransport.connectionConfig();
        var requestConfig = ApacheSourceHttpTransport.requestConfig(requestTimeout);
        URI uri = URI.create("https://source.example/path?query=1");
        var request = ApacheSourceHttpTransport.request(uri, requestConfig);

        assertThat(connectionConfig.getConnectTimeout().toDuration())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(requestConfig.isRedirectsEnabled()).isFalse();
        assertThat(requestConfig.getProxy()).isNull();
        assertThat(requestConfig.getResponseTimeout().toDuration()).isEqualTo(requestTimeout);
        assertThat(request.getUri()).isEqualTo(uri);
        assertThat(request.getConfig()).isSameAs(requestConfig);
        assertThat(request.getFirstHeader("User-Agent").getValue())
                .isEqualTo(RemoteConnectorSupport.ROBOT_USER_AGENT);
    }

    @Test
    void mapsSuccessfulResponseWithoutFollowingDocumentLinks() {
        byte[] body = "<html><body>Notice<a href=\"/linked\">linked</a></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        StubTransport transport = StubTransport.responding(
                200,
                Map.of(
                        "Content-Type", "text/html; charset=UTF-8",
                        "Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT"),
                new TrackingInputStream(body));
        String sourceUrl = "https://example.edu/docs/report%3Ffinal.html";

        List<CrawledDocument> documents = crawler(transport, 1024).crawl(source(sourceUrl), false);

        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.sourceUrl()).isEqualTo(sourceUrl);
            assertThat(document.filename()).isEqualTo("report_final.html");
            assertThat(document.contentType()).isEqualTo("text/html; charset=UTF-8");
            assertThat(document.lastModified())
                    .isEqualTo(OffsetDateTime.parse("2015-10-21T07:28:00Z"));
            assertThat(document.rawBytes()).isEqualTo(body);
        });
        assertThat(transport.getCount()).isEqualTo(1);
        assertThat(transport.lastUri()).isEqualTo(URI.create(sourceUrl));
    }

    @Test
    void fetchesAgainForEveryInvocationRegardlessOfForce() {
        AtomicInteger requests = new AtomicInteger();
        StubTransport transport = new StubTransport((uri, timeout) -> {
            int request = requests.incrementAndGet();
            return response(200, Map.of(), new TrackingInputStream(
                    ("body-" + request).getBytes(StandardCharsets.UTF_8)));
        });
        SourceRecord source = source("https://example.edu/fresh");
        SourceCrawler crawler = crawler(transport, 1024);

        assertThat(crawler.crawl(source, false).getFirst().rawBytes())
                .isEqualTo("body-1".getBytes(StandardCharsets.UTF_8));
        assertThat(crawler.crawl(source, false).getFirst().rawBytes())
                .isEqualTo("body-2".getBytes(StandardCharsets.UTF_8));
        assertThat(crawler.crawl(source, true).getFirst().rawBytes())
                .isEqualTo("body-3".getBytes(StandardCharsets.UTF_8));
        assertThat(requests).hasValue(3);
    }

    @Test
    void rejectsNonSuccessStatusesWithSanitizedFailure() {
        for (int status : List.of(503)) {
            TrackingInputStream body = new TrackingInputStream(
                    "SECRET-SERVER-DIAGNOSTIC".getBytes(StandardCharsets.UTF_8));
            StubTransport transport = StubTransport.responding(status, Map.of("Location", "/target"), body);

            assertIngestFailure(
                    () -> crawler(transport, 1024).crawl(
                            source("https://example.edu/private?access_token=SECRET-TOKEN"), false),
                    "SECRET-SERVER-DIAGNOSTIC", "SECRET-TOKEN", "/target", Integer.toString(status));
            assertThat(transport.getCount()).isEqualTo(1);
            assertThat(body.closed()).isTrue();
            assertThat(transport.lastResponse().closed()).isTrue();
        }
    }

    @Test
    void rejectsChunkedBodyAsSoonAsItExceedsConfiguredLimit() {
        StubTransport transport = StubTransport.responding(
                200,
                Map.of(),
                new TrackingInputStream("123456789".getBytes(StandardCharsets.UTF_8)));

        assertIngestFailure(
                () -> crawler(transport, 8).crawl(source("https://example.edu/oversized"), false),
                "123456789");
    }

    @Test
    void rejectsDeclaredOversizedBodyWithoutReadingIt() {
        TrackingInputStream body = new TrackingInputStream("small".getBytes(StandardCharsets.UTF_8));
        StubTransport transport = StubTransport.responding(
                200, Map.of("Content-Length", "1025"), body);

        assertIngestFailure(
                () -> crawler(transport, 1024).crawl(source("https://example.edu/oversized"), false),
                "1025");
        assertThat(body.bytesRead()).isZero();
        assertThat(body.closed()).isTrue();
    }

    @Test
    void rejectsNonHttpAndRelativeSourceUrlsBeforeTransport() {
        StubTransport transport = StubTransport.responding(
                200, Map.of(), new TrackingInputStream(new byte[]{1}));
        for (String invalidUrl : List.of(
                "ftp://example.edu/file.txt",
                "file:///private.txt",
                "/relative")) {
            assertIngestFailure(
                    () -> crawler(transport, 1024).crawl(source(invalidUrl), false),
                    invalidUrl);
        }
        assertThat(transport.getCount()).isZero();
    }

    @Test
    void wrapsTransportErrorsWithoutLeakingTheirDetails() {
        StubTransport transport = StubTransport.failing(
                new IOException("socket failed for SECRET-INTERNAL-HOST"));

        assertIngestFailure(
                () -> crawler(transport, 1024).crawl(
                        source("https://example.edu/notices"), false),
                "SECRET-INTERNAL-HOST", "example.edu");
        assertThat(transport.getCount()).isEqualTo(1);
    }

    @Test
    void appliesRequestTimeoutAndClosesSuccessfulResponse() {
        TrackingInputStream body = new TrackingInputStream("body".getBytes(StandardCharsets.UTF_8));
        StubTransport transport = StubTransport.responding(200, Map.of(), body);
        Duration requestTimeout = Duration.ofMillis(321);

        new HttpSourceCrawler(transport, requestTimeout, 1024, TEST_TRUSTED)
                .crawl(source("https://example.edu/notices"), false);

        assertThat(transport.lastTimeout()).isEqualTo(requestTimeout);
        assertThat(body.closed()).isTrue();
        assertThat(transport.lastResponse().closed()).isTrue();
    }

    @Test
    void bodyReadHasADeadlineAfterHeadersHaveArrived() throws Exception {
        BlockingAfterFirstByteInputStream body = new BlockingAfterFirstByteInputStream();
        StubTransport transport = StubTransport.responding(200, Map.of(), body);
        HttpSourceCrawler crawler = new HttpSourceCrawler(
                transport, Duration.ofMillis(150), 1024, TEST_TRUSTED);

        long started = System.nanoTime();
        assertIngestFailure(
                () -> crawler.crawl(source("https://example.edu/stalled"), false),
                "example.edu");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isLessThan(1_000L);
        assertThat(body.closed()).isTrue();
        assertThat(body.readerExited()).isTrue();
    }

    @Test
    void productionPolicyRejectsNonPublicLiteralBeforeTransport() {
        StubTransport transport = StubTransport.responding(
                200, Map.of(), new TrackingInputStream(new byte[]{1}));
        HttpSourceCrawler crawler = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, HostAddressPolicy.production());

        for (String url : List.of(
                "http://127.0.0.1/file",
                "http://10.0.0.1/file",
                "http://100.64.0.1/file",
                "http://169.254.169.254/latest/meta-data",
                "http://192.0.2.1/file",
                "http://198.18.0.1/file",
                "http://224.0.0.1/file",
                "http://[::1]/file",
                "http://[fc00::1]/file")) {
            assertIngestFailure(() -> crawler.crawl(source(url), false), url);
        }
        assertThat(transport.getCount()).isZero();
    }

    @Test
    void strictPolicyRejectsAHostWhenAnyResolvedAddressIsNonPublic() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        HostAddressPolicy mixedResolution = HostAddressPolicy.strict(
                host -> new InetAddress[]{publicAddress, privateAddress});
        StubTransport transport = StubTransport.responding(
                200, Map.of(), new TrackingInputStream(new byte[]{1}));
        HttpSourceCrawler crawler = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, mixedResolution);

        assertIngestFailure(
                () -> crawler.crawl(source("https://mixed.example.edu/document"), false),
                "mixed.example.edu");
        assertThat(transport.getCount()).isZero();
    }

    @Test
    void cleartextHostnameIsRejectedWithoutDnsResolution() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        AtomicInteger resolutions = new AtomicInteger();
        HostAddressPolicy policy = HostAddressPolicy.strict(host -> {
            resolutions.incrementAndGet();
            return new InetAddress[]{publicAddress};
        });
        StubTransport transport = StubTransport.responding(
                200, Map.of(), new TrackingInputStream(new byte[]{1}));

        assertIngestFailure(
                () -> new HttpSourceCrawler(transport, Duration.ofSeconds(1), 1024, policy)
                        .crawl(source("http://public.example/document"), false),
                "public.example");

        assertThat(transport.getCount()).isZero();
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void productionPolicyLeavesHttpsHostnameResolutionToTransport() {
        StubTransport transport = StubTransport.responding(
                200,
                Map.of("Content-Type", "text/plain"),
                new TrackingInputStream("ok".getBytes(StandardCharsets.UTF_8)));
        HttpSourceCrawler crawler = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, HostAddressPolicy.production());

        List<CrawledDocument> result = crawler.crawl(
                source("https://public.example/document"), false);

        assertThat(result).singleElement().satisfies(document ->
                assertThat(document.rawBytes()).isEqualTo("ok".getBytes(StandardCharsets.UTF_8)));
        assertThat(transport.getCount()).isEqualTo(1);
    }

    @Test
    void cleartextPublicIpLiteralIsAllowedWithoutDnsResolution() {
        HostAddressPolicy policy = HostAddressPolicy.strict(host -> {
            throw new AssertionError("IP literals must not be resolved through DNS");
        });
        StubTransport transport = StubTransport.responding(
                200, Map.of(), new TrackingInputStream("ok".getBytes(StandardCharsets.UTF_8)));

        List<CrawledDocument> result = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, policy)
                .crawl(source("http://8.8.8.8/document"), false);

        assertThat(result).hasSize(1);
        assertThat(transport.getCount()).isEqualTo(1);
    }

    @Test
    void rejectsUserInfoAndSanitizesDnsResolutionFailures() {
        StubTransport transport = StubTransport.responding(
                200, Map.of(), new TrackingInputStream(new byte[]{1}));
        HttpSourceCrawler trustedCrawler = crawler(transport, 1024);
        assertIngestFailure(
                () -> trustedCrawler.crawl(
                        source("https://user:SECRET@example.edu/document"), false),
                "user", "SECRET", "example.edu");

        HostAddressPolicy failedResolution = HostAddressPolicy.strict(host -> {
            throw new java.net.UnknownHostException("SECRET-DNS-DETAIL");
        });
        HttpSourceCrawler strictCrawler = new HttpSourceCrawler(
                transport, Duration.ofSeconds(1), 1024, failedResolution);
        assertThatThrownBy(() -> strictCrawler.crawl(
                        source("https://unresolved.example.edu/document"), false))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getMessage()).isEqualTo(FAILURE_MESSAGE);
                    assertThat(exception.getMessage()).doesNotContain("SECRET-DNS-DETAIL");
                    assertThat(exception.getCause()).isNotNull();
                });
        assertThat(transport.getCount()).isZero();
    }

    @Test
    void interruptedTransportRestoresInterruptFlagAndKeepsGenericMessage() {
        StubTransport transport = StubTransport.interrupted(
                new InterruptedException("SECRET-INTERRUPT-DETAIL"));
        Thread.interrupted();
        try {
            assertThatThrownBy(() -> crawler(transport, 1024).crawl(
                            source("https://example.edu/notices"), false))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getMessage()).isEqualTo(FAILURE_MESSAGE);
                        assertThat(exception.getMessage()).doesNotContain("SECRET-INTERRUPT-DETAIL");
                        assertThat(exception.getCause()).isInstanceOf(InterruptedException.class);
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void crawledDocumentDefensivelyCopiesRawBytes() {
        byte[] sourceBytes = "original".getBytes(StandardCharsets.UTF_8);
        CrawledDocument document = new CrawledDocument(
                "https://example.edu/document",
                "document.txt",
                "text/plain",
                null,
                sourceBytes);

        sourceBytes[0] = 'X';
        byte[] returnedBytes = document.rawBytes();
        returnedBytes[1] = 'Y';

        assertThat(document.rawBytes()).isEqualTo("original".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void crawledDocumentRejectsBlankFieldsAndOversizedContent() {
        byte[] oneByte = {1};

        assertThatThrownBy(() -> document(" ", "file", "type", oneByte))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document("https://example.edu", " ", "type", oneByte))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document("https://example.edu", "file", " ", oneByte))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document("https://example.edu", "file", "type", new byte[0]).rawBytes())
                .isEmpty();
        assertThatThrownBy(() -> document(
                "https://example.edu",
                "file",
                "type",
                new byte[CrawledDocument.MAX_RAW_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpSourceCrawler crawler(StubTransport transport, int maxRawBytes) {
        return new HttpSourceCrawler(
                transport, Duration.ofSeconds(2), maxRawBytes, TEST_TRUSTED);
    }

    private SourceRecord source(String url) {
        return new SourceRecord(1L, "Test source", url, "HTTP", true, null, null);
    }

    private CrawledDocument document(
            String sourceUrl,
            String filename,
            String contentType,
            byte[] bytes
    ) {
        return new CrawledDocument(sourceUrl, filename, contentType, null, bytes);
    }

    private void assertIngestFailure(ThrowingOperation operation, String... forbiddenMessageParts) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getMessage()).isEqualTo(FAILURE_MESSAGE);
                    assertThat(exception.getMessage()).doesNotContain(forbiddenMessageParts);
                });
    }

    private static StubResponse response(
            int status,
            Map<String, String> headers,
            InputStream body
    ) {
        return new StubResponse(status, headers, body);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ResponseFactory {
        StubResponse open(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    private static final class StubTransport implements SourceHttpTransport {

        private final ResponseFactory factory;
        private int getCount;
        private URI lastUri;
        private Duration lastTimeout;
        private StubResponse lastResponse;

        private StubTransport(ResponseFactory factory) {
            this.factory = factory;
        }

        static StubTransport responding(
                int status,
                Map<String, String> headers,
                InputStream body
        ) {
            return new StubTransport((uri, timeout) -> response(status, headers, body));
        }

        static StubTransport failing(IOException failure) {
            return new StubTransport((uri, timeout) -> {
                throw failure;
            });
        }

        static StubTransport interrupted(InterruptedException failure) {
            return new StubTransport((uri, timeout) -> {
                throw failure;
            });
        }

        @Override
        public Response get(URI uri, Duration timeout) throws IOException, InterruptedException {
            getCount++;
            lastUri = uri;
            lastTimeout = timeout;
            lastResponse = factory.open(uri, timeout);
            return lastResponse;
        }

        int getCount() {
            return getCount;
        }

        URI lastUri() {
            return lastUri;
        }

        Duration lastTimeout() {
            return lastTimeout;
        }

        StubResponse lastResponse() {
            return lastResponse;
        }
    }

    private static final class StubResponse implements SourceHttpTransport.Response {

        private final int status;
        private final Map<String, String> headers;
        private final InputStream body;
        private boolean closed;

        private StubResponse(int status, Map<String, String> headers, InputStream body) {
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
            return body;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (body != null) {
                body.close();
            }
        }

        boolean closed() {
            return closed;
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;
        private int bytesRead;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean closed() {
            return closed;
        }

        int bytesRead() {
            return bytesRead;
        }
    }

    private static final class BlockingAfterFirstByteInputStream extends InputStream {

        private final CountDownLatch readerExited = new CountDownLatch(1);
        private boolean first = true;
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            if (first) {
                first = false;
                return 'x';
            }
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("reader interrupted", exception);
                }
            }
            readerExited.countDown();
            return -1;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        synchronized boolean closed() {
            return closed;
        }

        boolean readerExited() throws InterruptedException {
            return readerExited.await(1, TimeUnit.SECONDS);
        }
    }
}

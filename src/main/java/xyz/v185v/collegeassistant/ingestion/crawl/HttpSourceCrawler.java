package xyz.v185v.collegeassistant.ingestion.crawl;

import xyz.v185v.collegeassistant.ingestion.RemoteCrawlQueue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.SourceRecord;

/** 受保护的单资源 HTTP 爬虫，也是发现连接器共用的抓取基础操作。 */
@Component
public class HttpSourceCrawler implements SourceCrawler {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_RAW_BYTES = CrawledDocument.MAX_RAW_BYTES;

    private static final String FAILURE_MESSAGE = "Source crawl failed";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final String DEFAULT_FILENAME = "download";
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final Semaphore GLOBAL_REQUESTS = new Semaphore(8, true);
    private static final Pattern UNSAFE_FILENAME_CHARACTERS =
            Pattern.compile("[\\p{Cntrl}<>:\"/\\\\|?*]");
    private static final Pattern UNSAFE_FILENAME_EDGES = Pattern.compile("^[. ]+|[. ]+$");
    private static final Pattern WINDOWS_RESERVED_FILENAME = Pattern.compile(
            "(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$");

    private final SourceHttpTransport transport;
    private final Duration requestTimeout;
    private final int maxRawBytes;
    private final HostAddressPolicy hostAddressPolicy;
    private final DocumentRepository documentRepository;
    private final SourceNetworkPolicyProperties networkProperties;
    private final RemoteCrawlQueue crawlQueue;
    private final Semaphore deploymentRequests;
    private final ConcurrentHashMap<String, AtomicLong> nextOriginRequestNanos =
            new ConcurrentHashMap<>();

    @Autowired
    public HttpSourceCrawler(
            DocumentRepository documentRepository,
            SourceNetworkPolicyProperties networkProperties,
            RemoteCrawlQueue crawlQueue
    ) {
        this(
                new ApacheSourceHttpTransport(InetAddress::getAllByName, networkProperties),
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_MAX_RAW_BYTES,
                HostAddressPolicy.deployment(networkProperties, InetAddress::getAllByName),
                documentRepository,
                networkProperties,
                crawlQueue);
    }

    HttpSourceCrawler(SourceHttpTransport transport, Duration requestTimeout, int maxRawBytes) {
        this(transport, requestTimeout, maxRawBytes, HostAddressPolicy.production(), null,
                legacyProperties());
    }

    HttpSourceCrawler(
            SourceHttpTransport transport,
            Duration requestTimeout,
            int maxRawBytes,
            HostAddressPolicy hostAddressPolicy
    ) {
        this(transport, requestTimeout, maxRawBytes, hostAddressPolicy, null, legacyProperties());
    }

    HttpSourceCrawler(
            SourceHttpTransport transport,
            Duration requestTimeout,
            int maxRawBytes,
            HostAddressPolicy hostAddressPolicy,
            DocumentRepository documentRepository,
            SourceNetworkPolicyProperties networkProperties
    ) {
        this(transport, requestTimeout, maxRawBytes, hostAddressPolicy,
                documentRepository, networkProperties, null);
    }

    HttpSourceCrawler(
            SourceHttpTransport transport,
            Duration requestTimeout,
            int maxRawBytes,
            HostAddressPolicy hostAddressPolicy,
            DocumentRepository documentRepository,
            SourceNetworkPolicyProperties networkProperties,
            RemoteCrawlQueue crawlQueue
    ) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxRawBytes <= 0 || maxRawBytes > CrawledDocument.MAX_RAW_BYTES) {
            throw new IllegalArgumentException("maxRawBytes is outside the supported range");
        }
        this.hostAddressPolicy = Objects.requireNonNull(
                hostAddressPolicy, "hostAddressPolicy must not be null");
        this.documentRepository = documentRepository;
        this.networkProperties = Objects.requireNonNull(
                networkProperties, "networkProperties must not be null");
        this.crawlQueue = crawlQueue;
        this.deploymentRequests = new Semaphore(networkProperties.getGlobalConcurrency(), true);
        this.requestTimeout = requestTimeout;
        this.maxRawBytes = maxRawBytes;
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
        URI requested = requireSourceUri(source == null ? null : source.baseUrl());
        return crawlUrl(source, requested, context, force);
    }

    List<CrawledDocument> crawlUrl(
            SourceRecord source,
            URI requested,
            CrawlContext context,
            boolean force
    ) {
        requested = requireSourceUri(requested == null ? null : requested.toASCIIString());
        String identityUrl = requested.toASCIIString();
        DocumentRecord existing = documentRepository == null || source == null || source.id() == null
                ? null : documentRepository.findBySourceIdAndSourceUrl(source.id(), identityUrl)
                        .orElse(null);
        RequestValidators validators = force || existing == null
                ? RequestValidators.none()
                : new RequestValidators(existing.etag(), existing.httpLastModified());
        try {
            if (crawlQueue != null) {
                crawlQueue.persist(context, requested);
            }
            FetchedResource fetched = fetchResource(requested, validators,
                    context == null ? maxRawBytes : (int) Math.min(maxRawBytes, context.maxBytes()));
            String filename = safeFilename(fetched.uri());
            if (fetched.notModified()) {
                String etag = fetched.etag() == null && existing != null
                        ? existing.etag() : fetched.etag();
                OffsetDateTime lastModified = fetched.lastModified() == null && existing != null
                        ? existing.httpLastModified() : fetched.lastModified();
                return List.of(CrawledDocument.notModified(
                        identityUrl, filename, fetched.contentType(), etag, lastModified));
            }
            byte[] bytes = fetched.rawBytes();
            return List.of(new CrawledDocument(
                    identityUrl, filename, fetched.contentType(), fetched.etag(),
                    fetched.lastModified(), sha256(bytes), bytes));
        } catch (CrawlDeferredException exception) {
            if (crawlQueue != null) {
                crawlQueue.deferUnfinished(context, exception.retryAt());
            }
            throw exception;
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ingestFailure(exception);
        } catch (IOException | RuntimeException exception) {
            throw ingestFailure(exception);
        }
    }

    FetchedResource fetchResource(
            URI requested,
            RequestValidators validators,
            int byteLimit
    ) throws IOException, InterruptedException {
        if (byteLimit < 1 || byteLimit > CrawledDocument.MAX_RAW_BYTES) {
            throw new IllegalArgumentException("byteLimit is invalid");
        }
        URI current = requireHttpUri(requested);
        RequestValidators safeValidators = validators == null
                ? RequestValidators.none() : validators;
        for (int redirect = 0; redirect <= networkProperties.getMaxRedirects(); redirect++) {
            hostAddressPolicy.validate(current);
            awaitRequestPermit(current);
            try (SourceHttpTransport.Response response =
                         transport.get(current, requestTimeout, safeValidators)) {
                int status = response.statusCode();
                if (isRedirect(status)) {
                    if (redirect == networkProperties.getMaxRedirects()) {
                        throw new IOException("Redirect limit exceeded");
                    }
                    String location = response.firstHeader("Location")
                            .map(String::strip)
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> new IOException("Redirect location is missing"));
                    current = requireHttpUri(current.resolve(location));
                    continue;
                }
                String contentType = contentType(response);
                String etag = safeEtag(response.firstHeader("ETag").orElse(null));
                OffsetDateTime lastModified = lastModified(response);
                if (status == 304) {
                    return FetchedResource.notModified(current, contentType, etag, lastModified);
                }
                if (status == 429) {
                    throw new CrawlDeferredException(retryAt(response));
                }
                if (status < 200 || status >= 300
                        || declaredLengthExceedsLimit(response, byteLimit)) {
                    throw new IOException("Remote response was rejected");
                }
                InputStream body = response.body();
                if (body == null) {
                    throw new IOException("Remote response body is missing");
                }
                try (body) {
                    return new FetchedResource(
                            current, status, contentType, etag, lastModified,
                            response.firstHeader("Content-Encoding").orElse(null),
                            readBodyWithDeadline(body, byteLimit));
                }
            } finally {
                deploymentRequests.release();
                GLOBAL_REQUESTS.release();
            }
        }
        throw new IOException("Redirect limit exceeded");
    }

    URI validateTarget(URI uri) throws IOException {
        return requireHttpUri(uri);
    }

    private URI requireSourceUri(String value) {
        try {
            if (value == null) {
                throw new IllegalArgumentException("source URL is required");
            }
            return requireHttpUri(URI.create(value));
        } catch (IOException | RuntimeException exception) {
            throw ingestFailure(exception);
        }
    }

    private URI requireHttpUri(URI value) throws IOException {
        try {
            URI canonical = UrlCanonicalizer.canonicalize(value);
            hostAddressPolicy.validate(canonical);
            return canonical;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Source URI is invalid", exception);
        }
    }

    private void awaitRequestPermit(URI uri) throws InterruptedException {
        GLOBAL_REQUESTS.acquire();
        boolean deploymentAcquired = false;
        try {
            deploymentRequests.acquire();
            deploymentAcquired = true;
            Duration delay = networkProperties.getSameOriginDelay();
            if (delay.isZero()) {
                return;
            }
            AtomicLong next = nextOriginRequestNanos.computeIfAbsent(
                    UrlCanonicalizer.origin(uri), ignored -> new AtomicLong());
            while (true) {
                long now = System.nanoTime();
                long scheduled = next.get();
                long start = Math.max(now, scheduled);
                if (next.compareAndSet(scheduled, start + delay.toNanos())) {
                    long wait = start - now;
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    return;
                }
            }
        } catch (InterruptedException exception) {
            if (deploymentAcquired) {
                deploymentRequests.release();
            }
            GLOBAL_REQUESTS.release();
            throw exception;
        }
    }

    private byte[] readBodyWithDeadline(InputStream input, int byteLimit)
            throws InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> readTask = executor.submit(() -> readBody(input, byteLimit));
        try {
            return readTask.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            closeQuietly(input);
            readTask.cancel(true);
            throw ingestFailure(exception);
        } catch (InterruptedException exception) {
            closeQuietly(input);
            readTask.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            throw ingestFailure(cause == null ? exception : cause);
        } finally {
            executor.close();
        }
    }

    private boolean declaredLengthExceedsLimit(
            SourceHttpTransport.Response response,
            int byteLimit
    ) {
        return response.firstHeader("Content-Length")
                .map(String::strip)
                .map(value -> {
                    try {
                        return Long.parseLong(value) > byteLimit;
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private byte[] readBody(InputStream input, int byteLimit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(byteLimit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int permittedRead = Math.min(buffer.length, byteLimit - total + 1);
            int read = input.read(buffer, 0, permittedRead);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                int next = input.read();
                if (next < 0) {
                    return output.toByteArray();
                }
                if (++total > byteLimit) {
                    throw new IOException("Remote response exceeded byte limit");
                }
                output.write(next);
                continue;
            }
            total += read;
            if (total > byteLimit) {
                throw new IOException("Remote response exceeded byte limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
// 保留原始的有界读取失败。
        }
    }

    private String safeFilename(URI sourceUri) {
        String path = sourceUri.getPath();
        String candidate = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        candidate = UNSAFE_FILENAME_CHARACTERS.matcher(candidate).replaceAll("_");
        candidate = UNSAFE_FILENAME_EDGES.matcher(candidate.strip()).replaceAll("");
        if (candidate.isBlank()) {
            return DEFAULT_FILENAME;
        }
        if (WINDOWS_RESERVED_FILENAME.matcher(candidate).matches()) {
            candidate = "_" + candidate;
        }
        if (candidate.length() > MAX_FILENAME_LENGTH) {
            int end = MAX_FILENAME_LENGTH;
            if (Character.isHighSurrogate(candidate.charAt(end - 1))) {
                end--;
            }
            candidate = candidate.substring(0, end);
        }
        return candidate;
    }

    private String contentType(SourceHttpTransport.Response response) {
        return response.firstHeader("Content-Type")
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .orElse(DEFAULT_CONTENT_TYPE);
    }

    private String safeEtag(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new RequestValidators(value, null).etag();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private OffsetDateTime lastModified(SourceHttpTransport.Response response) {
        return response.firstHeader("Last-Modified")
                .map(String::strip)
                .flatMap(this::parseHttpDate)
                .orElse(null);
    }

    private Optional<OffsetDateTime> parseHttpDate(String value) {
        try {
            return Optional.of(ZonedDateTime.parse(
                    value, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private OffsetDateTime retryAt(SourceHttpTransport.Response response) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Duration requested = response.firstHeader("Retry-After")
                .map(String::strip)
                .flatMap(value -> parseRetryAfter(value, now))
                .orElse(Duration.ofSeconds(1));
        Duration capped = requested.compareTo(networkProperties.getMaxRetryAfter()) > 0
                ? networkProperties.getMaxRetryAfter() : requested;
        if (capped.isNegative() || capped.isZero()) {
            capped = Duration.ofSeconds(1);
        }
        return now.plus(capped);
    }

    private Optional<Duration> parseRetryAfter(String value, OffsetDateTime now) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            return parseHttpDate(value).map(date -> Duration.between(now, date));
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private BusinessException ingestFailure(Throwable cause) {
        return cause == null
                ? new BusinessException(ErrorCode.INGEST_FAILED, FAILURE_MESSAGE)
                : new BusinessException(ErrorCode.INGEST_FAILED, FAILURE_MESSAGE, cause);
    }

    private static SourceNetworkPolicyProperties legacyProperties() {
        SourceNetworkPolicyProperties properties = new SourceNetworkPolicyProperties();
        properties.setSameOriginDelay(Duration.ZERO);
        return properties;
    }

    record FetchedResource(
            URI uri,
            int statusCode,
            String contentType,
            String etag,
            OffsetDateTime lastModified,
            String contentEncoding,
            byte[] rawBytes
    ) {
        FetchedResource {
            Objects.requireNonNull(uri, "uri");
            contentType = contentType == null || contentType.isBlank()
                    ? DEFAULT_CONTENT_TYPE : contentType.strip();
            rawBytes = rawBytes == null ? null : rawBytes.clone();
        }

        static FetchedResource notModified(
                URI uri,
                String contentType,
                String etag,
                OffsetDateTime lastModified
        ) {
            return new FetchedResource(uri, 304, contentType, etag, lastModified, null, null);
        }

        boolean notModified() {
            return statusCode == 304;
        }

        @Override
        public byte[] rawBytes() {
            return rawBytes == null ? null : rawBytes.clone();
        }
    }
}

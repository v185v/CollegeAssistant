package xyz.v185v.collegeassistant.ingestion.crawl;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

/** 每次请求固定一个已校验 DNS 快照的 Apache HttpClient 传输层。 */
final class ApacheSourceHttpTransport implements SourceHttpTransport {

    static final Duration CONNECT_TIMEOUT = HttpSourceCrawler.DEFAULT_CONNECT_TIMEOUT;

    private final HostAddressResolver resolver;
    private final SourceNetworkPolicyProperties properties;

    ApacheSourceHttpTransport() {
        this(InetAddress::getAllByName, new SourceNetworkPolicyProperties());
    }

    ApacheSourceHttpTransport(HostAddressResolver resolver) {
        this(resolver, new SourceNetworkPolicyProperties());
    }

    ApacheSourceHttpTransport(
            HostAddressResolver resolver,
            SourceNetworkPolicyProperties properties
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Response get(URI uri, Duration requestTimeout) throws IOException {
        return get(uri, requestTimeout, RequestValidators.none());
    }

    @Override
    public Response get(
            URI uri,
            Duration requestTimeout,
            RequestValidators validators
    ) throws IOException {
        AtomicReference<InetAddress[]> snapshot = new AtomicReference<>();
        HostAddressResolver snapshottingResolver = host -> {
            InetAddress[] resolved = snapshot.get();
            if (resolved == null) {
                resolved = resolver.resolve(host);
                snapshot.compareAndSet(null, resolved == null ? null : resolved.clone());
                resolved = snapshot.get();
            }
            return resolved == null ? null : resolved.clone();
        };
        HostAddressPolicy.deployment(properties, snapshottingResolver).validate(uri);
        String hostname = HostAddressPolicy.normalizeHost(uri.getHost());
        boolean allowPrivate = properties.permitsPrivate(uri);
        ValidatingDnsResolver validatingResolver = new ValidatingDnsResolver(
                hostname, snapshottingResolver, allowPrivate);
        ConnectionConfig connectionConfig = connectionConfig();
        PoolingHttpClientConnectionManager manager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(validatingResolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .build();
        RequestConfig requestConfig = requestConfig(requestTimeout);
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableContentCompression()
                .build();
        HttpGet request = request(uri, requestConfig, validators);

        try {
            ClassicHttpResponse response = client.executeOpen(
                    null, request, HttpClientContext.create());
            return new ApacheResponse(response, client, manager);
        } catch (IOException | RuntimeException exception) {
            closeAfterFailure(client, manager, exception);
            throw exception;
        }
    }

    static ConnectionConfig connectionConfig() {
        return ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                .build();
    }

    static RequestConfig requestConfig(Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        return RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(requestTimeout))
                .build();
    }

    static HttpGet request(URI uri, RequestConfig requestConfig) {
        return request(uri, requestConfig, RequestValidators.none());
    }

    static HttpGet request(
            URI uri,
            RequestConfig requestConfig,
            RequestValidators validators
    ) {
        HttpGet request = new HttpGet(uri);
        request.setConfig(requestConfig);
        request.setHeader("User-Agent", RemoteConnectorSupport.ROBOT_USER_AGENT);
        RequestValidators safeValidators = validators == null
                ? RequestValidators.none() : validators;
        if (safeValidators.etag() != null) {
            request.setHeader("If-None-Match", safeValidators.etag());
        }
        if (safeValidators.lastModified() != null) {
            request.setHeader(
                    "If-Modified-Since",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            safeValidators.lastModified().atZoneSameInstant(ZoneOffset.UTC)));
        }
        return request;
    }

    private static void closeAfterFailure(
            CloseableHttpClient client,
            PoolingHttpClientConnectionManager manager,
            Throwable failure
    ) {
        try {
            client.close();
        } catch (IOException closeException) {
            failure.addSuppressed(closeException);
        } finally {
            manager.close();
        }
    }

    private static final class ApacheResponse implements Response {

        private final ClassicHttpResponse response;
        private final CloseableHttpClient client;
        private final PoolingHttpClientConnectionManager manager;
        private boolean closed;

        private ApacheResponse(
                ClassicHttpResponse response,
                CloseableHttpClient client,
                PoolingHttpClientConnectionManager manager
        ) {
            this.response = response;
            this.client = client;
            this.manager = manager;
        }

        @Override
        public int statusCode() {
            return response.getCode();
        }

        @Override
        public Optional<String> firstHeader(String name) {
            Header header = response.getFirstHeader(name);
            return header == null ? Optional.empty() : Optional.of(header.getValue());
        }

        @Override
        public InputStream body() throws IOException {
            HttpEntity entity = response.getEntity();
            return entity == null ? null : entity.getContent();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                response.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                client.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            } finally {
                manager.close();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}

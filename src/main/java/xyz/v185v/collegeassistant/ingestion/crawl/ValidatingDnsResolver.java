package xyz.v185v.collegeassistant.ingestion.crawl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

import org.apache.hc.client5.http.DnsResolver;

/** 解析一次预期主机，并固定经过校验的公开地址快照。 */
public final class ValidatingDnsResolver implements DnsResolver {

    private final String expectedHostname;
    private final HostAddressResolver delegate;
    private final boolean allowNonPublic;

    private volatile InetAddress[] snapshot;
    private IOException resolutionFailure;
    private boolean attempted;

    public ValidatingDnsResolver(
            String expectedHostname,
            HostAddressResolver delegate
    ) {
        this(expectedHostname, delegate, false);
    }

    public ValidatingDnsResolver(
            String expectedHostname,
            HostAddressResolver delegate,
            boolean allowNonPublic
    ) {
        if (expectedHostname == null || expectedHostname.isBlank()) {
            throw new IllegalArgumentException("expectedHostname must not be blank");
        }
        this.expectedHostname = expectedHostname;
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.allowNonPublic = allowNonPublic;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        requireExpectedHost(host);
        InetAddress[] resolved = snapshot;
        if (resolved != null) {
            return resolved.clone();
        }

        synchronized (this) {
            if (snapshot != null) {
                return snapshot.clone();
            }
            if (attempted) {
                throw resolutionFailure();
            }
            attempted = true;
            try {
                InetAddress[] addresses = delegate.resolve(expectedHostname);
                if (addresses == null || addresses.length == 0) {
                    throw new IOException("Source host did not resolve");
                }
                if (!allowNonPublic) {
                    for (InetAddress address : addresses) {
                        HostAddressPolicy.requirePublic(address);
                    }
                }
                snapshot = addresses.clone();
                return snapshot.clone();
            } catch (IOException | RuntimeException exception) {
                resolutionFailure = exception instanceof IOException ioException
                        ? ioException : new IOException("Source host resolution failed", exception);
                throw resolutionFailure();
            }
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        requireExpectedHost(host);
        return expectedHostname;
    }

    private void requireExpectedHost(String host) throws UnknownHostException {
        if (host == null || !expectedHostname.equalsIgnoreCase(host)) {
            throw new UnknownHostException("Unexpected source hostname");
        }
    }

    private UnknownHostException resolutionFailure() {
        UnknownHostException exception = new UnknownHostException("Source hostname is not publicly resolvable");
        if (resolutionFailure != null) {
            exception.initCause(resolutionFailure);
        }
        return exception;
    }
}

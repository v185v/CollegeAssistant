package xyz.v185v.collegeassistant.ingestion.crawl;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 由部署方管理的网络限制；数据源 API 载荷不会填充此对象。 */
@ConfigurationProperties(prefix = "app.ingestion")
public class SourceNetworkPolicyProperties {

    private List<String> privateOriginAllowlist = List.of();
    private Duration sameOriginDelay = Duration.ofMillis(500);
    private Duration maxRetryAfter = Duration.ofHours(1);
    private int globalConcurrency = 8;
    private int maxRedirects = 5;

    public List<String> getPrivateOriginAllowlist() {
        return privateOriginAllowlist;
    }

    public void setPrivateOriginAllowlist(List<String> privateOriginAllowlist) {
        this.privateOriginAllowlist = privateOriginAllowlist == null
                ? List.of() : List.copyOf(privateOriginAllowlist);
    }

    public Duration getSameOriginDelay() {
        return sameOriginDelay;
    }

    public void setSameOriginDelay(Duration sameOriginDelay) {
        this.sameOriginDelay = positiveOrZero(sameOriginDelay, "sameOriginDelay");
    }

    public Duration getMaxRetryAfter() {
        return maxRetryAfter;
    }

    public void setMaxRetryAfter(Duration maxRetryAfter) {
        if (maxRetryAfter == null || maxRetryAfter.isNegative() || maxRetryAfter.isZero()) {
            throw new IllegalArgumentException("maxRetryAfter must be positive");
        }
        this.maxRetryAfter = maxRetryAfter;
    }

    public int getGlobalConcurrency() {
        return globalConcurrency;
    }

    public void setGlobalConcurrency(int globalConcurrency) {
        if (globalConcurrency < 1 || globalConcurrency > 8) {
            throw new IllegalArgumentException("globalConcurrency must be between 1 and 8");
        }
        this.globalConcurrency = globalConcurrency;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        if (maxRedirects < 0 || maxRedirects > 10) {
            throw new IllegalArgumentException("maxRedirects must be between 0 and 10");
        }
        this.maxRedirects = maxRedirects;
    }

    public Set<String> allowedPrivateOrigins() {
        Set<String> normalized = new LinkedHashSet<>();
        for (String configured : privateOriginAllowlist) {
            if (configured == null || configured.isBlank() || configured.indexOf('*') >= 0) {
                throw new IllegalArgumentException("Private origin allowlist entries must be exact origins");
            }
            URI raw = URI.create(configured.strip());
            URI canonical = UrlCanonicalizer.canonicalize(raw);
            String path = canonical.getPath();
            if (raw.getUserInfo() != null || raw.getRawQuery() != null || raw.getRawFragment() != null
                    || path != null && !path.equals("/")) {
                throw new IllegalArgumentException("Private origin allowlist entries must not contain paths");
            }
            normalized.add(UrlCanonicalizer.origin(canonical));
        }
        return Set.copyOf(normalized);
    }

    public boolean permitsPrivate(URI uri) {
        return allowedPrivateOrigins().contains(UrlCanonicalizer.origin(uri));
    }

    private Duration positiveOrZero(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}

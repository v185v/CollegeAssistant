package xyz.v185v.collegeassistant.ai;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.ingestion.crawl.UrlCanonicalizer;

/** 面向管理员控制模型端点的精确来源 SSRF 策略。 */
@Component
public class ModelHostPolicy {

    private static final Map<String, Set<String>> PROVIDER_ORIGINS = Map.of(
            "openai", Set.of("https://api.openai.com:443"),
            "deepseek", Set.of("https://api.deepseek.com:443"),
            "zhipu", Set.of("https://open.bigmodel.cn:443"),
            "dashscope", Set.of("https://dashscope.aliyuncs.com:443"),
            "ollama", Set.of());

    private final ModelHostPolicyProperties properties;

    public ModelHostPolicy(ModelHostPolicyProperties properties) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
    }

    public URI requireAllowed(String provider, String rawBaseUrl) {
        String normalizedProvider = normalizeProvider(provider);
        Set<String> configuredOrigins = configuredOrigins();
        URI raw = parse(rawBaseUrl);
        if (raw.getUserInfo() != null || raw.getRawFragment() != null
                || raw.getRawQuery() != null || rawBaseUrl.indexOf('*') >= 0) {
            throw validation("Model base URL must not contain credentials, query, or fragment");
        }
        URI canonical;
        try {
            canonical = UrlCanonicalizer.canonicalize(raw);
        } catch (IllegalArgumentException exception) {
            throw validation("Model base URL is invalid", exception);
        }
        String origin = UrlCanonicalizer.origin(canonical);
        boolean providerOwned = PROVIDER_ORIGINS.get(normalizedProvider).contains(origin);
        boolean deploymentAllowed = configuredOrigins.contains(origin);
        if (!providerOwned && !deploymentAllowed) {
            throw validation("Model base URL origin is not allowed");
        }
        if ("http".equalsIgnoreCase(canonical.getScheme()) && !deploymentAllowed) {
            throw validation("Cleartext model origins require an exact deployment allowlist entry");
        }
        return canonical;
    }

    private Set<String> configuredOrigins() {
        Set<String> result = new LinkedHashSet<>();
        for (String entry : properties.getModelHostAllowlist()) {
            if (entry == null || entry.isBlank() || entry.indexOf('*') >= 0) {
                throw validation("Model host allowlist entries must be exact origins");
            }
            URI raw = parse(entry.strip());
            if (raw.getUserInfo() != null || raw.getRawQuery() != null
                    || raw.getRawFragment() != null) {
                throw validation("Model host allowlist entries must be exact origins");
            }
            URI canonical;
            try {
                canonical = UrlCanonicalizer.canonicalize(raw);
            } catch (IllegalArgumentException exception) {
                throw validation("Model host allowlist is invalid", exception);
            }
            if (canonical.getPath() != null && !"/".equals(canonical.getPath())) {
                throw validation("Model host allowlist entries must not contain paths");
            }
            result.add(UrlCanonicalizer.origin(canonical));
        }
        return Set.copyOf(result);
    }

    private URI parse(String value) {
        if (value == null || value.isBlank()) {
            throw validation("Model base URL is required");
        }
        try {
            return URI.create(value.strip());
        } catch (IllegalArgumentException exception) {
            throw validation("Model URL is invalid", exception);
        }
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw validation("Model provider is required");
        }
        String normalized = provider.strip().toLowerCase(Locale.ROOT);
        if (!PROVIDER_ORIGINS.containsKey(normalized)) {
            throw validation("Unsupported model provider");
        }
        return normalized;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private BusinessException validation(String message, Throwable cause) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message, cause);
    }
}

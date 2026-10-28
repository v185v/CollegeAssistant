package xyz.v185v.collegeassistant.auth;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置项。
 *
 * @param secret           HMAC 签名密钥
 * @param expiresInSeconds 访问令牌有效秒数
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long expiresInSeconds
) {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MIN_DISTINCT_CODE_POINTS = 4;
    private static final Set<String> FORBIDDEN_SECRETS = Set.of(
            "dev-only-change-me-dev-only-change-me",
            "replace-with-a-random-secret-at-least-32-bytes");

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret is required");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT secret must contain at least 32 UTF-8 bytes");
        }
        if (FORBIDDEN_SECRETS.contains(secret)
                || secret.codePoints().distinct().count() < MIN_DISTINCT_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "JWT secret must not be a known or low-variation placeholder");
        }
        if (expiresInSeconds <= 0) {
            throw new IllegalArgumentException("JWT expiry must be positive");
        }
    }
}

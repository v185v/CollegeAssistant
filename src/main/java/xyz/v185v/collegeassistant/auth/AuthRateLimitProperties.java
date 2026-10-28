package xyz.v185v.collegeassistant.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证接口限流配置。 */
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public record AuthRateLimitProperties(
        Integer loginIpMaxRequests,
        Duration loginIpWindow,
        Integer loginFailureMaxRequests,
        Duration loginFailureWindow,
        Integer registerIpMaxRequests,
        Duration registerIpWindow,
        Integer maxTrackedKeys
) {

    static final int DEFAULT_LOGIN_IP_MAX_REQUESTS = 30;
    static final Duration DEFAULT_LOGIN_IP_WINDOW = Duration.ofMinutes(1);
    static final int DEFAULT_LOGIN_FAILURE_MAX_REQUESTS = 5;
    static final Duration DEFAULT_LOGIN_FAILURE_WINDOW = Duration.ofMinutes(5);
    static final int DEFAULT_REGISTER_IP_MAX_REQUESTS = 5;
    static final Duration DEFAULT_REGISTER_IP_WINDOW = Duration.ofHours(1);
    static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;

    public AuthRateLimitProperties {
        loginIpMaxRequests = loginIpMaxRequests == null
                ? DEFAULT_LOGIN_IP_MAX_REQUESTS : loginIpMaxRequests;
        loginIpWindow = loginIpWindow == null
                ? DEFAULT_LOGIN_IP_WINDOW : loginIpWindow;
        loginFailureMaxRequests = loginFailureMaxRequests == null
                ? DEFAULT_LOGIN_FAILURE_MAX_REQUESTS : loginFailureMaxRequests;
        loginFailureWindow = loginFailureWindow == null
                ? DEFAULT_LOGIN_FAILURE_WINDOW : loginFailureWindow;
        registerIpMaxRequests = registerIpMaxRequests == null
                ? DEFAULT_REGISTER_IP_MAX_REQUESTS : registerIpMaxRequests;
        registerIpWindow = registerIpWindow == null
                ? DEFAULT_REGISTER_IP_WINDOW : registerIpWindow;
        maxTrackedKeys = maxTrackedKeys == null
                ? DEFAULT_MAX_TRACKED_KEYS : maxTrackedKeys;

        requirePositive(loginIpMaxRequests, "Login IP request limit");
        requirePositive(loginIpWindow, "Login IP window");
        requirePositive(loginFailureMaxRequests, "Login failure limit");
        requirePositive(loginFailureWindow, "Login failure window");
        requirePositive(registerIpMaxRequests, "Registration IP request limit");
        requirePositive(registerIpWindow, "Registration IP window");
        requirePositive(maxTrackedKeys, "Maximum tracked keys");
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

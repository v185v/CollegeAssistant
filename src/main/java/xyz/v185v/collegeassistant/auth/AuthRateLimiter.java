package xyz.v185v.collegeassistant.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 为认证接口提供线程安全、有界的固定窗口限制。 */
@Component
public class AuthRateLimiter {

    private static final String UNKNOWN_ADDRESS = "unknown";
    private static final String RATE_LIMIT_MESSAGE = "请求过于频繁，请稍后再试";

    private final AuthRateLimitProperties properties;
    private final Clock clock;
    private final LinkedHashMap<String, Window> loginIpWindows = accessOrderedMap();
    private final LinkedHashMap<String, LoginWindow> loginFailureWindows = accessOrderedMap();
    private final LinkedHashMap<String, Window> registrationIpWindows = accessOrderedMap();

    @Autowired
    public AuthRateLimiter(AuthRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public AuthRateLimiter(AuthRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

/** 消耗来源 IP 窗口额度，并拒绝近期失败次数过多的账户。 */
    public void beforeLogin(String remoteAddress, String username) {
        String address = normalizeAddress(remoteAddress);
        consume(
                loginIpWindows,
                address,
                properties.loginIpMaxRequests(),
                properties.loginIpWindow());
        reserveLoginAttempt(
                loginFailureWindows,
                accountAddressKey(address, username),
                properties.loginFailureMaxRequests(),
                properties.loginFailureWindow());
    }

/** 记录规范化账户和来源地址的一次凭据校验失败。 */
    public void recordLoginFailure(String remoteAddress, String username) {
        completeFailedLogin(
                loginFailureWindows,
                accountAddressKey(normalizeAddress(remoteAddress), username),
                properties.loginFailureWindow());
    }

/** 登录成功后清除近期凭据失败记录。 */
    public void recordLoginSuccess(String remoteAddress, String username) {
        String key = accountAddressKey(normalizeAddress(remoteAddress), username);
        synchronized (loginFailureWindows) {
            loginFailureWindows.remove(key);
        }
    }

/** 认证未得到凭据结果时释放进行中的预留额度。 */
    public void releaseLoginAttempt(String remoteAddress, String username) {
        String key = accountAddressKey(normalizeAddress(remoteAddress), username);
        synchronized (loginFailureWindows) {
            Instant now = clock.instant();
            LoginWindow window = loginFailureWindows.get(key);
            if (window == null) {
                return;
            }
            if (window.isExpiredAt(now)) {
                loginFailureWindows.remove(key);
                return;
            }
            LoginWindow released = window.released();
            if (released.failures() == 0 && released.inFlight() == 0) {
                loginFailureWindows.remove(key);
            } else {
                loginFailureWindows.put(key, released);
            }
        }
    }

/** 消耗来源地址的注册窗口额度。 */
    public void beforeRegistration(String remoteAddress) {
        consume(
                registrationIpWindows,
                normalizeAddress(remoteAddress),
                properties.registerIpMaxRequests(),
                properties.registerIpWindow());
    }

    int loginIpKeyCount() {
        return size(loginIpWindows);
    }

    int loginFailureKeyCount() {
        return size(loginFailureWindows);
    }

    int registrationIpKeyCount() {
        return size(registrationIpWindows);
    }

    private void consume(
            LinkedHashMap<String, Window> windows,
            String key,
            int maxRequests,
            Duration duration
    ) {
        synchronized (windows) {
            Instant now = clock.instant();
            Window window = windows.get(key);
            if (window == null || window.isExpiredAt(now)) {
                putWindow(windows, key, new Window(1, now.plus(duration)));
                return;
            }
            if (window.count() >= maxRequests) {
                throw rateLimited();
            }
            windows.put(key, window.incremented());
        }
    }

    private void reserveLoginAttempt(
            LinkedHashMap<String, LoginWindow> windows,
            String key,
            int maxRequests,
            Duration duration
    ) {
        synchronized (windows) {
            Instant now = clock.instant();
            LoginWindow window = windows.get(key);
            if (window == null || window.isExpiredAt(now)) {
                putWindow(windows, key, new LoginWindow(0, 1, now.plus(duration)));
                return;
            }
            if (window.failures() + window.inFlight() >= maxRequests) {
                throw rateLimited();
            }
            windows.put(key, window.reserved());
        }
    }

    private void completeFailedLogin(
            LinkedHashMap<String, LoginWindow> windows,
            String key,
            Duration duration
    ) {
        synchronized (windows) {
            Instant now = clock.instant();
            LoginWindow window = windows.get(key);
            if (window == null || window.isExpiredAt(now)) {
                putWindow(windows, key, new LoginWindow(1, 0, now.plus(duration)));
                return;
            }
            windows.put(key, window.failed());
        }
    }

    private <T> void putWindow(
            LinkedHashMap<String, T> windows,
            String key,
            T window
    ) {
        if (!windows.containsKey(key) && windows.size() >= properties.maxTrackedKeys()) {
            Iterator<Map.Entry<String, T>> iterator = windows.entrySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        windows.put(key, window);
    }

    private int size(LinkedHashMap<String, ?> windows) {
        synchronized (windows) {
            return windows.size();
        }
    }

    private static String normalizeAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return UNKNOWN_ADDRESS;
        }
        return remoteAddress.strip();
    }

    private static String accountAddressKey(String address, String username) {
        String normalizedUsername = username == null
                ? "" : username.strip().toLowerCase(Locale.ROOT);
        return normalizedUsername + '\u0000' + address;
    }

    private static <T> LinkedHashMap<String, T> accessOrderedMap() {
        return new LinkedHashMap<>(16, 0.75f, true);
    }

    private static BusinessException rateLimited() {
        return new BusinessException(ErrorCode.RATE_LIMITED, RATE_LIMIT_MESSAGE);
    }

    private record Window(int count, Instant expiresAt) {

        boolean isExpiredAt(Instant instant) {
            return !instant.isBefore(expiresAt);
        }

        Window incremented() {
            return new Window(count + 1, expiresAt);
        }
    }

    private record LoginWindow(int failures, int inFlight, Instant expiresAt) {

        boolean isExpiredAt(Instant instant) {
            return !instant.isBefore(expiresAt);
        }

        LoginWindow reserved() {
            return new LoginWindow(failures, inFlight + 1, expiresAt);
        }

        LoginWindow failed() {
            return new LoginWindow(failures + 1, Math.max(0, inFlight - 1), expiresAt);
        }

        LoginWindow released() {
            return new LoginWindow(failures, Math.max(0, inFlight - 1), expiresAt);
        }
    }
}

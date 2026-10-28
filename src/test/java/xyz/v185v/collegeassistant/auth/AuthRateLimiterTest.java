package xyz.v185v.collegeassistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class AuthRateLimiterTest {

    private static final String MESSAGE = "请求过于频繁，请稍后再试";

    private final AuthRateLimitProperties properties = new AuthRateLimitProperties(
            30, Duration.ofMinutes(1),
            5, Duration.ofMinutes(5),
            5, Duration.ofHours(1),
            10_000);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void limitsLoginRequestsPerIpToThirtyPerMinute() {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());

        for (int i = 0; i < 30; i++) {
            limiter.beforeLogin("192.0.2.10", "user-" + i);
        }

        assertRateLimited(() -> limiter.beforeLogin("192.0.2.10", "overflow-user"));
    }

    @Test
    void limitsFailedLoginsPerNormalizedAccountAndIp() {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());

        for (int i = 0; i < 5; i++) {
            limiter.beforeLogin("192.0.2.10", " Alice ");
            limiter.recordLoginFailure("192.0.2.10", " Alice ");
        }

        assertRateLimited(() -> limiter.beforeLogin("192.0.2.10", "ALICE"));
    }

    @Test
    void limitsRegistrationsPerIpToFivePerHour() {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());

        for (int i = 0; i < 5; i++) {
            limiter.beforeRegistration("192.0.2.10");
        }

        assertRateLimited(() -> limiter.beforeRegistration("192.0.2.10"));
    }

    @Test
    void expiredWindowsAreReplacedAndAllowRequestsAgain() {
        MutableClock clock = new MutableClock();
        AuthRateLimiter limiter = new AuthRateLimiter(properties, clock);

        for (int i = 0; i < 30; i++) {
            limiter.beforeLogin("192.0.2.10", "user-" + i);
        }
        for (int i = 0; i < 5; i++) {
            limiter.beforeRegistration("192.0.2.12");
        }
        for (int i = 0; i < 5; i++) {
            limiter.recordLoginFailure("192.0.2.11", "alice");
        }

        clock.advance(Duration.ofMinutes(1));
        limiter.beforeLogin("192.0.2.10", "alice");

        clock.advance(Duration.ofMinutes(4));
        limiter.beforeLogin("192.0.2.11", "alice");

        clock.advance(Duration.ofHours(1));
        limiter.beforeRegistration("192.0.2.12");
    }

    @Test
    void isolatesAccountsAndAddressesAndClearsFailuresOnSuccess() {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());

        for (int i = 0; i < 5; i++) {
            limiter.recordLoginFailure("192.0.2.10", "alice");
        }
        assertRateLimited(() -> limiter.beforeLogin("192.0.2.10", "alice"));

        limiter.beforeLogin("192.0.2.11", "alice");
        limiter.beforeLogin("192.0.2.10", "bob");

        limiter.recordLoginSuccess("192.0.2.10", "alice");
        limiter.beforeLogin("192.0.2.10", "alice");
    }

    @Test
    void normalizesUnknownAddressAndUsername() {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());

        for (int i = 0; i < 5; i++) {
            limiter.recordLoginFailure(null, "  Alice ");
        }

        assertRateLimited(() -> limiter.beforeLogin(" ", "alice"));
    }

    @Test
    void concurrentLoginAcquisitionsRemainAtomic() throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());
        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch ready = new CountDownLatch(100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 100; i++) {
                String username = "user-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        limiter.beforeLogin("192.0.2.10", username);
                        return true;
                    } catch (BusinessException ex) {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(30);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLoginReservationsCapAccountAndIpBeforeResultsAreKnown() throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());
        ExecutorService executor = Executors.newFixedThreadPool(30);
        CountDownLatch ready = new CountDownLatch(30);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 30; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        limiter.beforeLogin("192.0.2.10", "alice");
                        return true;
                    } catch (BusinessException ex) {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releasingAbortedLoginAttemptsRestoresAccountCapacity() {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());

        for (int i = 0; i < 5; i++) {
            limiter.beforeLogin("192.0.2.10", "alice");
        }
        assertRateLimited(() -> limiter.beforeLogin("192.0.2.10", "alice"));

        for (int i = 0; i < 5; i++) {
            limiter.releaseLoginAttempt("192.0.2.10", "alice");
        }

        limiter.beforeLogin("192.0.2.10", "alice");
    }

    @Test
    void boundsEachPolicyMapAtTenThousandKeys() {
        AuthRateLimiter limiter = new AuthRateLimiter(properties, new MutableClock());

        for (int i = 0; i < 10_001; i++) {
            String address = "198.51.100." + i;
            limiter.beforeRegistration(address);
            limiter.beforeLogin(address, "alice");
            limiter.recordLoginFailure(address, "alice");
        }

        assertThat(limiter.registrationIpKeyCount()).isEqualTo(10_000);
        assertThat(limiter.loginIpKeyCount()).isEqualTo(10_000);
        assertThat(limiter.loginFailureKeyCount()).isEqualTo(10_000);
    }

    @Test
    void defaultPropertiesUseDocumentedLimits() {
        AuthRateLimitProperties defaults = new AuthRateLimitProperties(
                null, null, null, null, null, null, null);

        assertThat(defaults.loginIpMaxRequests()).isEqualTo(30);
        assertThat(defaults.loginIpWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.loginFailureMaxRequests()).isEqualTo(5);
        assertThat(defaults.loginFailureWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.registerIpMaxRequests()).isEqualTo(5);
        assertThat(defaults.registerIpWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(defaults.maxTrackedKeys()).isEqualTo(10_000);
    }

    @Test
    void configuredPropertiesOverrideDefaults() {
        contextRunner.withPropertyValues(
                        "app.auth.rate-limit.login-ip-max-requests=7",
                        "app.auth.rate-limit.login-ip-window=PT2M",
                        "app.auth.rate-limit.login-failure-max-requests=3",
                        "app.auth.rate-limit.login-failure-window=PT7M",
                        "app.auth.rate-limit.register-ip-max-requests=4",
                        "app.auth.rate-limit.register-ip-window=PT2H",
                        "app.auth.rate-limit.max-tracked-keys=123")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    AuthRateLimitProperties configured = context.getBean(AuthRateLimitProperties.class);
                    assertThat(configured.loginIpMaxRequests()).isEqualTo(7);
                    assertThat(configured.loginIpWindow()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(configured.loginFailureMaxRequests()).isEqualTo(3);
                    assertThat(configured.loginFailureWindow()).isEqualTo(Duration.ofMinutes(7));
                    assertThat(configured.registerIpMaxRequests()).isEqualTo(4);
                    assertThat(configured.registerIpWindow()).isEqualTo(Duration.ofHours(2));
                    assertThat(configured.maxTrackedKeys()).isEqualTo(123);
                });
    }

    private void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(exception.getMessage()).isEqualTo(MESSAGE);
                });
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthRateLimitProperties.class)
    static class BindingConfiguration {
    }
}

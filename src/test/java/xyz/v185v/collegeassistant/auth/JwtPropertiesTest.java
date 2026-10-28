package xyz.v185v.collegeassistant.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class JwtPropertiesTest {

    private static final String STRONG_SECRET =
            "4c9035e746d14aa6a8f4b59a921ec797eec14b23136f4fd3";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void rejectsMissingSecret() {
        assertBindingFails("app.jwt.expires-in-seconds=7200");
    }

    @Test
    void rejectsBlankSecret() {
        String blankSecret = "\u2000\u2001\u2002\u2003".repeat(3);
        assertThat(blankSecret).isBlank();

        assertBindingFails(
                "app.jwt.secret=" + blankSecret,
                "app.jwt.expires-in-seconds=7200");
    }

    @Test
    void rejectsHistoricDefaultSecret() {
        assertBindingFails(
                "app.jwt.secret=dev-only-change-me-dev-only-change-me",
                "app.jwt.expires-in-seconds=7200");
    }

    @Test
    void rejectsDocumentationPlaceholderSecret() {
        assertBindingFails(
                "app.jwt.secret=replace-with-a-random-secret-at-least-32-bytes",
                "app.jwt.expires-in-seconds=7200");
    }

    @Test
    void rejectsThirtyOneByteSecret() {
        assertBindingFails(
                "app.jwt.secret=0123456789abcdefghijklmnopqrstu",
                "app.jwt.expires-in-seconds=7200");
    }

    @Test
    void rejectsSecretWithFewerThanFourDistinctCodePoints() {
        assertBindingFails(
                "app.jwt.secret=" + "ab".repeat(16),
                "app.jwt.expires-in-seconds=7200");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveExpiry(long expiresInSeconds) {
        assertBindingFails(
                "app.jwt.secret=" + STRONG_SECRET,
                "app.jwt.expires-in-seconds=" + expiresInSeconds);
    }

    @Test
    void bindsStrongSecretAndPositiveExpiry() {
        contextRunner.withPropertyValues(
                        "app.jwt.secret=" + STRONG_SECRET,
                        "app.jwt.expires-in-seconds=7200")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    JwtProperties properties = context.getBean(JwtProperties.class);
                    assertThat(properties.secret()).isEqualTo(STRONG_SECRET);
                    assertThat(properties.expiresInSeconds()).isEqualTo(7200);
                });
    }

    private void assertBindingFails(String... propertyValues) {
        contextRunner.withPropertyValues(propertyValues)
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class BindingConfiguration {
    }
}

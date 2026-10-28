package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RagPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void minimumSimilarityDefaultsToPointThirtyFiveAndCanBeConfigured() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(RagProperties.class).minSimilarity()).isEqualTo(0.35);
        });
        contextRunner
                .withPropertyValues("app.rag.min-similarity=0.6")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBean(RagProperties.class).minSimilarity()).isEqualTo(0.6);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01", "NaN", "Infinity"})
    void invalidMinimumSimilarityFailsConfigurationBinding(String value) {
        contextRunner
                .withPropertyValues("app.rag.min-similarity=" + value)
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RagProperties.class)
    static class BindingConfiguration {
    }
}

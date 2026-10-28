package xyz.v185v.collegeassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.AiProperties;

class AiPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void remoteProvidersCannotInheritLocalModelOrBaseUrlDefaultsDuringBinding() {
        contextRunner
                .withPropertyValues(
                        "ai.chat.provider=openai",
                        "ai.chat.api-key=test-chat-key",
                        "ai.embedding.provider=zhipu",
                        "ai.embedding.api-key=test-embedding-key")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    AiProperties properties = context.getBean(AiProperties.class);

                    assertModelProviderFailure(() -> new ModelFactory(properties).chatModel());
                    assertModelProviderFailure(() -> new ModelFactory(properties).embeddingModel());
                });
    }

    @Test
    void configurationToStringDoesNotExposeApiKeys() {
        String secret = "sk-never-print-this";
        AiProperties properties = new AiProperties();
        properties.getChat().setApiKey(secret);
        properties.getEmbedding().setApiKey(secret);

        assertThat(properties.toString()).doesNotContain(secret);
        assertThat(properties.getChat().toString()).doesNotContain(secret);
        assertThat(properties.getEmbedding().toString()).doesNotContain(secret);
    }

    private void assertModelProviderFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.MODEL_PROVIDER_FAILED));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class BindingConfiguration {
    }
}

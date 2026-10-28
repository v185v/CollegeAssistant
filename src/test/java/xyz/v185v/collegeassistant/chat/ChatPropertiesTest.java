package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ChatPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void draftCleanupDefaultsKeepOneHourTtlBeyondTwoMinuteStreamTimeout() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            ChatProperties properties = context.getBean(ChatProperties.class);
            assertThat(properties.streamTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.draftTtl()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.draftCleanupBatchSize()).isEqualTo(100);
            assertThat(properties.draftCleanupPeriod()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.memoryMessageLimit()).isEqualTo(8);
            assertThat(properties.detailMessageLimit()).isEqualTo(200);
            assertThat(properties.draftTtl()).isGreaterThan(properties.streamTimeout());
        });
    }

    @Test
    void draftCleanupSettingsAreConfigurable() {
        contextRunner.withPropertyValues(
                        "app.chat.stream-timeout=PT3M",
                        "app.chat.draft-ttl=PT2H",
                        "app.chat.draft-cleanup-batch-size=25",
                        "app.chat.draft-cleanup-period=PT10M",
                        "app.chat.memory-message-limit=12",
                        "app.chat.detail-message-limit=300")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    ChatProperties properties = context.getBean(ChatProperties.class);
                    assertThat(properties.streamTimeout()).isEqualTo(Duration.ofMinutes(3));
                    assertThat(properties.draftTtl()).isEqualTo(Duration.ofHours(2));
                    assertThat(properties.draftCleanupBatchSize()).isEqualTo(25);
                    assertThat(properties.draftCleanupPeriod()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.memoryMessageLimit()).isEqualTo(12);
                    assertThat(properties.detailMessageLimit()).isEqualTo(300);
                });
    }

    @Test
    void draftTtlMustBeStrictlyLongerThanSseTimeout() {
        for (String ttl : new String[] {"PT2M", "PT1M"}) {
            contextRunner.withPropertyValues(
                            "app.chat.stream-timeout=PT2M",
                            "app.chat.draft-ttl=" + ttl)
                    .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        }
    }

    @Test
    void historyLimitsStayWithinHardSafetyBounds() {
        for (String property : new String[] {
                "app.chat.memory-message-limit=0",
                "app.chat.memory-message-limit=51",
                "app.chat.detail-message-limit=0",
                "app.chat.detail-message-limit=501"
        }) {
            contextRunner.withPropertyValues(property)
                    .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatProperties.class)
    static class BindingConfiguration {
    }
}

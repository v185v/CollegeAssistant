package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(OutputCaptureExtension.class)
class ChatDraftReconcilerTest {

    private static final OffsetDateTime EXPECTED_CUTOFF =
            OffsetDateTime.parse("2026-07-14T11:00:00Z");

    @Test
    void scheduledCleanupUsesConfiguredPeriod() throws Exception {
        Method method = ChatDraftReconciler.class.getMethod("cleanupStalePlaceholders");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${app.chat.draft-cleanup-period:PT5M}");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.chat.draft-cleanup-period:PT5M}");
    }

    @Test
    void cleanupDeletesOnlyRowsOlderThanConfiguredTtlInFiniteBatch() {
        ChatRepository repository = mock(ChatRepository.class);
        ChatProperties properties = new ChatProperties(
                Duration.ofMinutes(2),
                Duration.ofHours(1),
                25,
                Duration.ofMinutes(5),
                8,
                200);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
        ChatDraftReconciler reconciler =
                new ChatDraftReconciler(repository, properties, clock);

        reconciler.cleanupStalePlaceholders();

        verify(repository).deleteStaleAssistantPlaceholders(
                EXPECTED_CUTOFF, 25);
    }

    @Test
    void cleanupFailureIsSanitizedAndTheNextScheduledCycleCanRetry(
            CapturedOutput output
    ) {
        ChatRepository repository = mock(ChatRepository.class);
        when(repository.deleteStaleAssistantPlaceholders(EXPECTED_CUTOFF, 25))
                .thenThrow(new IllegalStateException(
                        "jdbc:postgresql://db?password=DO-NOT-LOG"))
                .thenReturn(0);
        ChatDraftReconciler reconciler = reconciler(repository);

        assertThatCode(reconciler::cleanupStalePlaceholders).doesNotThrowAnyException();
        assertThatCode(reconciler::cleanupStalePlaceholders).doesNotThrowAnyException();

        verify(repository, times(2))
                .deleteStaleAssistantPlaceholders(EXPECTED_CUTOFF, 25);
        assertThat(output)
                .contains("stage=delete-stale")
                .contains("causeType=java.lang.IllegalStateException")
                .doesNotContain("DO-NOT-LOG");
    }

    @Test
    void chatSchedulingIsEnabledWithoutDependingOnUgcConfiguration() {
        assertThat(ChatSchedulingConfiguration.class
                .getAnnotation(EnableScheduling.class)).isNotNull();
    }

    private ChatDraftReconciler reconciler(ChatRepository repository) {
        ChatProperties properties = new ChatProperties(
                Duration.ofMinutes(2),
                Duration.ofHours(1),
                25,
                Duration.ofMinutes(5),
                8,
                200);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
        return new ChatDraftReconciler(repository, properties, clock);
    }
}

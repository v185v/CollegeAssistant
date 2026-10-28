package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.document.SourceRecord;

class SourceScheduleCalculatorTest {

    private final SourceScheduleCalculator calculator = new SourceScheduleCalculator();

    @Test
    void manualSourcesDoNotHaveANextRun() {
        assertThat(calculator.nextRunAt(source(
                SourceScheduleType.MANUAL, "Asia/Shanghai", null),
                Instant.parse("2026-07-25T00:30:00Z"))).isNull();
    }

    @Test
    void dailyAndWeeklySchedulesCalculateTheNextOccurrenceInTheirZone() {
        Instant after = Instant.parse("2026-07-25T00:30:00Z");

        assertThat(calculator.nextRunAt(source(
                SourceScheduleType.DAILY, "Asia/Shanghai", "0 0 9 * * *"), after))
                .isEqualTo(OffsetDateTime.parse("2026-07-25T01:00:00Z"));
        assertThat(calculator.nextRunAt(source(
                SourceScheduleType.WEEKLY, "Asia/Shanghai", "0 30 8 * * MON"), after))
                .isEqualTo(OffsetDateTime.parse("2026-07-27T00:30:00Z"));
    }

    @Test
    void cronSchedulesRespectTheConfiguredTimezone() {
        Instant after = Instant.parse("2026-07-25T12:30:00Z");

        assertThat(calculator.nextRunAt(source(
                SourceScheduleType.CRON, "America/New_York", "0 0 9 * * *"), after))
                .isEqualTo(OffsetDateTime.parse("2026-07-25T13:00:00Z"));
    }

    @Test
    void scheduledExpressionsMustNotRunMoreOftenThanEveryFifteenMinutes() {
        assertThatThrownBy(() -> calculator.validateAndNormalize(
                SourceScheduleType.CRON,
                "0 */5 * * * *",
                "Asia/Shanghai",
                Instant.parse("2026-07-25T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15 minutes");
    }

    @Test
    void cronKeepsTheAdministratorExpressionWhileDailyAndWeeklyAreCanonicalized() {
        assertThat(calculator.validateAndNormalize(
                SourceScheduleType.CRON,
                "0 15 9 * * MON-FRI",
                "Asia/Shanghai",
                Instant.parse("2026-07-25T00:00:00Z")))
                .isEqualTo("0 15 9 * * MON-FRI");
        assertThat(calculator.validateAndNormalize(
                SourceScheduleType.DAILY,
                "0 0 9 * * *",
                "Asia/Shanghai",
                Instant.parse("2026-07-25T00:00:00Z")))
                .isEqualTo("0 0 9 * * *");
    }

    private SourceRecord source(
            SourceScheduleType scheduleType,
            String zone,
            String cronExpression
    ) {
        return new SourceRecord(
                10L,
                "Scheduled source",
                "https://catalog.example.edu/scheduled",
                "OFFICIAL",
                true,
                SourceConnectorType.SINGLE_URL,
                "NONE",
                scheduleType,
                zone,
                cronExpression,
                true,
                3,
                500,
                4,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }
}

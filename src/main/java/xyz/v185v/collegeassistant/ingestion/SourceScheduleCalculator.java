package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.document.SourceRecord;

/** 校验持久化数据源计划并计算下一次墙上时钟时间。 */
@Component
public class SourceScheduleCalculator {

    private static final Duration MINIMUM_INTERVAL = Duration.ofMinutes(15);

    /**
* 在持久化前校验数据源计划。CRON 保留管理员输入的精确表达式，
* 预定义的每日和每周策略使用 Spring 的规范形式。
     */
    public String validateAndNormalize(
            SourceScheduleType scheduleType,
            String rawExpression,
            String rawZone,
            Instant referenceTime
    ) {
        if (scheduleType == null) {
            throw new IllegalArgumentException("schedule type is required");
        }
        ZoneId zone = parseZone(rawZone);
        if (scheduleType == SourceScheduleType.MANUAL) {
            if (rawExpression != null && !rawExpression.isBlank()) {
                throw new IllegalArgumentException("Manual schedules must not define a cron expression");
            }
            return null;
        }
        String expressionText = rawExpression == null ? "" : rawExpression.strip();
        if (expressionText.isBlank()) {
            throw new IllegalArgumentException("Scheduled sources require a cron expression");
        }
        CronExpression expression;
        try {
            expression = CronExpression.parse(expressionText);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Source cron expression is invalid", exception);
        }
        assertMinimumInterval(expression, zone, referenceTime);
        return scheduleType == SourceScheduleType.CRON ? expressionText : expression.toString();
    }

/** 返回严格晚于 {@code after} 的首次发生时间；MANUAL 数据源返回 null。 */
    public OffsetDateTime nextRunAt(SourceRecord source, Instant after) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        return nextRunAt(source.scheduleType(), source.cronExpression(), source.scheduleZone(), after);
    }

/** 根据持久化数据源字段返回严格晚于 {@code after} 的首次发生时间。 */
    public OffsetDateTime nextRunAt(
            SourceScheduleType scheduleType,
            String cronExpression,
            String scheduleZone,
            Instant after
    ) {
        if (after == null) {
            throw new IllegalArgumentException("schedule reference time is required");
        }
        String normalized = validateAndNormalize(scheduleType, cronExpression, scheduleZone, after);
        if (normalized == null) {
            return null;
        }
        ZoneId zone = parseZone(scheduleZone);
        ZonedDateTime next = CronExpression.parse(normalized)
                .next(ZonedDateTime.ofInstant(after, zone));
        if (next == null) {
            throw new IllegalArgumentException("Source cron expression has no future occurrence");
        }
        return next.toOffsetDateTime();
    }

    private void assertMinimumInterval(
            CronExpression expression,
            ZoneId zone,
            Instant referenceTime
    ) {
        Instant reference = referenceTime == null ? Instant.now() : referenceTime;
        ZonedDateTime first = expression.next(ZonedDateTime.ofInstant(reference, zone));
        ZonedDateTime second = first == null ? null : expression.next(first);
        if (first == null || second == null
                || Duration.between(first.toInstant(), second.toInstant())
                        .compareTo(MINIMUM_INTERVAL) < 0) {
            throw new IllegalArgumentException("Cron schedules must run no more often than every 15 minutes");
        }
    }

    private ZoneId parseZone(String rawZone) {
        if (rawZone == null || rawZone.isBlank()) {
            throw new IllegalArgumentException("Source schedule zone is required");
        }
        try {
            return ZoneId.of(rawZone);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Source schedule zone is invalid", exception);
        }
    }
}

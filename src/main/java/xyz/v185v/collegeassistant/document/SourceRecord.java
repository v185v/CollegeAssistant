package xyz.v185v.collegeassistant.document;

import java.time.OffsetDateTime;
import java.util.List;

import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;

/** 受管摄取数据源的持久化配置和生命周期状态。 */
public record SourceRecord(
        Long id,
        String name,
        String baseUrl,
        String type,
        boolean enabled,
        SourceConnectorType connectorType,
        String deletionStatus,
        SourceScheduleType scheduleType,
        String scheduleZone,
        String cronExpression,
        boolean sameHostOnly,
        int maxDepth,
        int maxPages,
        int maxConcurrency,
        List<String> includePatterns,
        List<String> excludePatterns,
        OffsetDateTime nextRunAt,
        OffsetDateTime lastRunAt,
        Long createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

/** 为现有单 URL 爬虫和测试保留的兼容构造函数。 */
    public SourceRecord(
            Long id,
            String name,
            String baseUrl,
            String type,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(
                id, name, baseUrl, type, enabled, SourceConnectorType.SINGLE_URL, "NONE",
                SourceScheduleType.MANUAL, "Asia/Shanghai", null, true, 3, 500, 4,
                List.of(), List.of(), null, null, null, createdAt, updatedAt);
    }

    public SourceRecord {
        requirePositive(id, "Source id");
        requireText(name, 128, "Source name");
        if (baseUrl != null) {
            baseUrl = baseUrl.strip();
            if (baseUrl.isEmpty()) {
                baseUrl = null;
            }
        }
        requireText(type, 32, "Source type");
        if (connectorType == null) {
            throw new IllegalArgumentException("Source connectorType must not be null");
        }
        requireText(deletionStatus, 32, "Source deletionStatus");
        if (scheduleType == null) {
            throw new IllegalArgumentException("Source scheduleType must not be null");
        }
        requireText(scheduleZone, 64, "Source scheduleZone");
        if (cronExpression != null && cronExpression.length() > 128) {
            throw new IllegalArgumentException("Source cronExpression must not exceed 128 characters");
        }
        if (maxDepth < 0 || maxDepth > 32) {
            throw new IllegalArgumentException("Source maxDepth must be between 0 and 32");
        }
        if (maxPages < 1 || maxPages > 100000) {
            throw new IllegalArgumentException("Source maxPages must be between 1 and 100000");
        }
        if (maxConcurrency < 1 || maxConcurrency > 64) {
            throw new IllegalArgumentException("Source maxConcurrency must be between 1 and 64");
        }
        includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
        excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
        requirePositive(createdBy, "Source createdBy");
    }

    private static void requirePositive(Long value, String field) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireText(String value, Integer maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (maxLength != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
    }
}

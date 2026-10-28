package xyz.v185v.collegeassistant.admin.source;

import java.time.OffsetDateTime;
import java.util.List;

import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;

/** 数据源配置及当前运行状态的安全管理投影。 */
public record SourceDto(
        Long id,
        String name,
        String type,
        SourceConnectorType connectorType,
        String baseUrl,
        boolean enabled,
        SourceScheduleType scheduleType,
        String scheduleZone,
        String cronExpression,
        boolean sameHostOnly,
        int maxDepth,
        int maxPages,
        int maxConcurrency,
        List<String> includePatterns,
        List<String> excludePatterns,
        String deletionStatus,
        OffsetDateTime nextRunAt,
        OffsetDateTime lastRunAt,
        IngestRunStatus latestRunStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public SourceDto {
        includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
        excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
    }
}

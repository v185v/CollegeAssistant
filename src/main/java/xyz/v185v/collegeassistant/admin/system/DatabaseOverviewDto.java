package xyz.v185v.collegeassistant.admin.system;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 面向管理员的脱敏数据库、存储和模型健康数据。 */
public record DatabaseOverviewDto(
        String databaseVersion,
        String pgvectorVersion,
        String flywayVersion,
        long databaseSizeBytes,
        long indexSizeBytes,
        Map<String, Long> tableCounts,
        int activeConnections,
        boolean storageAvailable,
        ModelStatus modelStatus,
        List<RecentIngestionFailure> recentIngestionFailures
) {
    public DatabaseOverviewDto {
        tableCounts = tableCounts == null ? Map.of() : Map.copyOf(tableCounts);
        recentIngestionFailures = recentIngestionFailures == null
                ? List.of()
                : List.copyOf(recentIngestionFailures);
    }

/** 明确排除凭据和提供商端点配置。 */
    public record ModelStatus(
            String provider,
            String model,
            boolean configured,
            int dimension
    ) {
    }

/** 明确排除可能包含敏感信息的原始摄取错误详情。 */
    public record RecentIngestionFailure(
            Long id,
            Long sourceId,
            String status,
            String errorCode,
            OffsetDateTime finishedAt
    ) {
    }
}

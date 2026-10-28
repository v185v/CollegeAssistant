package xyz.v185v.collegeassistant.admin.job;

import java.time.OffsetDateTime;
import java.util.Map;

/** 管理任务中的可安全重启工作单元。 */
public record AdminJobItemDto(
        Long id,
        Long jobId,
        String itemKey,
        String itemType,
        String targetType,
        Long targetId,
        AdminJobStatus status,
        int retryCount,
        String errorCode,
        String errorSummary,
        Map<String, Object> checkpoint,
        int checkpointAttempt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public AdminJobItemDto {
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
    }
}

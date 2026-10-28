package xyz.v185v.collegeassistant.admin.job;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 对外提供的管理任务详情，包括持久化条目检查点。 */
public record AdminJobDto(
        Long id,
        AdminJobType jobType,
        AdminJobStatus status,
        String targetType,
        Long targetId,
        Long requestedBy,
        int attempt,
        String workerId,
        @JsonIgnore
        UUID leaseToken,
        OffsetDateTime claimedAt,
        OffsetDateTime heartbeatAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorCode,
        String errorSummary,
        Map<String, Object> requestPayload,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<AdminJobItemDto> items
) {
    public AdminJobDto {
        requestPayload = requestPayload == null ? Map.of() : Map.copyOf(requestPayload);
        items = items == null ? List.of() : List.copyOf(items);
    }
}

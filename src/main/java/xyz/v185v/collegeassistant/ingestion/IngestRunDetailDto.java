package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.time.OffsetDateTime;
import java.util.List;

/** 摄取运行的完整运行详情，包括条目检查点和租约元数据。 */
public record IngestRunDetailDto(
        Long id,
        Long sourceId,
        IngestRunStatus status,
        String triggerType,
        OffsetDateTime scheduledAt,
        long discovered,
        long added,
        long updated,
        long skipped,
        long failed,
        int attempt,
        String workerId,
        OffsetDateTime claimedAt,
        OffsetDateTime heartbeatAt,
        String errorCode,
        String errorDetail,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<IngestRunItemDto> items
) {
    public IngestRunDetailDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

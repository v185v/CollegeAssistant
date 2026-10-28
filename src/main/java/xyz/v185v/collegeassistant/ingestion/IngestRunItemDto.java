package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.time.OffsetDateTime;
import java.util.Map;

/** 摄取运行中单个 URL 或文件的可安全重启检查点。 */
public record IngestRunItemDto(
        Long id,
        Long runId,
        String itemKey,
        String itemKind,
        IngestRunItemStatus status,
        Long documentId,
        int retryCount,
        OffsetDateTime nextAttemptAt,
        String errorCode,
        String errorSummary,
        Map<String, Object> checkpoint,
        int checkpointAttempt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public IngestRunItemDto {
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
    }
}

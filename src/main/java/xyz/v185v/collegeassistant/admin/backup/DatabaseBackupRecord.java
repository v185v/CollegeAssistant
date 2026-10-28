package xyz.v185v.collegeassistant.admin.backup;

import java.time.OffsetDateTime;

/** 内部数据库记录；不透明存储键不会由控制器直接返回。 */
public record DatabaseBackupRecord(
        Long id,
        Long jobId,
        DatabaseBackupStatus status,
        String format,
        String storageKey,
        String sha256,
        Long sizeBytes,
        Long requestedBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        int retentionDays,
        OffsetDateTime expiresAt,
        String errorSummary,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

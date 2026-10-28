package xyz.v185v.collegeassistant.admin.backup;

import java.time.OffsetDateTime;

/** 面向管理员的脱敏备份清单记录。 */
public record DatabaseBackupDto(
        Long id,
        Long jobId,
        DatabaseBackupStatus status,
        String format,
        String sha256,
        Long sizeBytes,
        Long requestedBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        int retentionDays,
        OffsetDateTime expiresAt,
        String errorSummary,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean downloadable
) {

    public static DatabaseBackupDto from(DatabaseBackupRecord record) {
        return new DatabaseBackupDto(
                record.id(), record.jobId(), record.status(), record.format(), record.sha256(),
                record.sizeBytes(), record.requestedBy(), record.startedAt(), record.finishedAt(),
                record.retentionDays(), record.expiresAt(), record.errorSummary(),
                record.createdAt(), record.updatedAt(),
                record.status().isDownloadable() && record.storageKey() != null);
    }
}

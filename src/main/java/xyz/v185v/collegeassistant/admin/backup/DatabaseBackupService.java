package xyz.v185v.collegeassistant.admin.backup;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobHandler;
import xyz.v185v.collegeassistant.admin.job.AdminJobLease;
import xyz.v185v.collegeassistant.admin.job.AdminJobLeaseLostException;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

/** 逻辑备份的授权、容量与租约保护发布边界。 */
@Service
public class DatabaseBackupService implements AdminJobHandler {

    private final DatabaseBackupRepository repository;
    private final PgBackupProcess process;
    private final BackupFileStore fileStore;
    private final BackupRetentionScheduler retention;
    private final BackupProperties properties;
    private final AdminAuditService auditService;

    @Autowired
    public DatabaseBackupService(
            DatabaseBackupRepository repository,
            PgBackupProcess process,
            BackupFileStore fileStore,
            BackupRetentionScheduler retention,
            BackupProperties properties,
            AdminAuditService auditService
    ) {
        this.repository = repository;
        this.process = process;
        this.fileStore = fileStore;
        this.retention = retention;
        this.properties = properties;
        this.auditService = auditService;
    }

    DatabaseBackupService(
            DatabaseBackupRepository repository,
            PgBackupProcess process,
            BackupFileStore fileStore,
            BackupRetentionScheduler retention,
            BackupProperties properties
    ) {
        this(repository, process, fileStore, retention, properties, null);
    }

    public PageResult<DatabaseBackupDto> list(AuthPrincipal actor, int page, int size) {
        requireAdmin(actor);
        PageResult<DatabaseBackupRecord> records = repository.findPage(page, size);
        return new PageResult<>(
                records.items().stream().map(DatabaseBackupDto::from).toList(),
                records.total(), records.page(), records.size(), records.pages());
    }

    public int defaultRetentionDays() {
        return properties.defaultRetentionDays();
    }

    public DatabaseBackupDto create(AuthPrincipal actor, int retentionDays) {
        requireAdmin(actor);
        if (retentionDays < 1 || retentionDays > 3_650) {
            throw validation("Backup retention must be between 1 and 3650 days");
        }
        AdminAuditEvent requested = AdminAuditEvent.requested(
                actor.userId(), "DATABASE_BACKUP_REQUESTED", "DATABASE_BACKUP", null,
                Map.of("retentionDays", retentionDays));
        try {
            ensureDumpCapability();
            retention.cleanupExpired();
            ensurePreDumpCapacity();
        } catch (RuntimeException failure) {
            recordFailureAudit(requested, failure);
            throw failure;
        }
        DatabaseBackupRecord created = auditedResult(
                requested,
                () -> repository.create(actor.userId(), retentionDays),
                record -> requested.withTargetId(record.id()).succeeded());
        return DatabaseBackupDto.from(created);
    }

    public DatabaseBackupDto validate(AuthPrincipal actor, Long backupId) {
        requireAdmin(actor);
        requirePositive(backupId);
        AdminAuditEvent requested = AdminAuditEvent.requested(
                actor.userId(), "DATABASE_BACKUP_VALIDATED", "DATABASE_BACKUP", backupId,
                Map.of());
        try {
            if (!process.restoreAvailable() || !fileStore.isAvailable()) {
                throw capabilityUnavailable();
            }
        } catch (RuntimeException failure) {
            recordFailureAudit(requested, failure);
            throw failure;
        }
        try {
            validateArtifact(backupId);
        } catch (RuntimeException failure) {
            markValidationFailed(backupId, failure);
            recordFailureAudit(requested, failure);
            throw failure;
        }
        try {
            return DatabaseBackupDto.from(audited(
                    requested, () -> repository.markValidated(backupId)));
        } catch (RuntimeException failure) {
            markValidationFailed(backupId, failure);
            throw failure;
        }
    }

    public BackupDownload openDownload(AuthPrincipal actor, Long backupId) {
        requireAdmin(actor);
        DatabaseBackupRecord backup = requireBackup(backupId);
        if (!backup.status().isDownloadable() || backup.storageKey() == null
                || backup.sizeBytes() == null) {
            throw conflict("Backup is unavailable for download");
        }
        InputStream input = fileStore.open(backup.storageKey());
        try {
            return new BackupDownload(
                    input, "college-backup-" + backup.id() + ".dump", backup.sizeBytes());
        } catch (RuntimeException failure) {
            closeQuietly(input);
            throw failure;
        }
    }

    public void delete(AuthPrincipal actor, Long backupId) {
        requireAdmin(actor);
        requirePositive(backupId);
        AdminAuditEvent requested = AdminAuditEvent.requested(
                actor.userId(), "DATABASE_BACKUP_DELETED", "DATABASE_BACKUP", backupId,
                Map.of());
        AtomicBoolean fileDeletionAttempted = new AtomicBoolean();
        try {
            audited(requested, () -> {
                DatabaseBackupRecord backup = repository.lockForDeletion(backupId)
                        .orElseThrow(this::backupNotFound);
                if (backup.storageKey() != null) {
                    fileDeletionAttempted.set(true);
                    fileStore.delete(backup.storageKey());
                }
                if (!repository.delete(backupId)) {
                    throw conflict("Backup state changed during deletion");
                }
                return null;
            });
        } catch (RuntimeException failure) {
            if (fileDeletionAttempted.get()) {
                try {
                    repository.markDeleteFailed(backupId, "Backup deletion failed");
                } catch (RuntimeException stateFailure) {
                    failure.addSuppressed(stateFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public AdminJobType type() {
        return AdminJobType.DATABASE_BACKUP;
    }

    @Override
    public void execute(AdminJobExecutionContext context) {
        Objects.requireNonNull(context, "context");
        Long backupId = context.job().targetId();
        if (backupId == null || backupId <= 0) {
            throw new IllegalStateException("Backup job target is invalid");
        }
        AdminJobLease lease = context.lease();
        context.inLeaseTransaction(operations -> {
            if (!repository.markRunning(backupId, lease)) {
                throw new AdminJobLeaseLostException();
            }
            return null;
        });

        Path partial = null;
        try {
            ensureDumpCapability();
            ensurePreDumpCapacity();
            partial = process.dump(lease);
            long attemptSize = fileStore.attemptSize(partial, lease);
            ensurePostDumpCapacity(attemptSize);
            context.assertActive();
            StoredBackupArtifact artifact =
                    fileStore.promoteAttemptAtomically(partial, lease);
            context.inLeaseTransaction(operations -> {
                if (!repository.publish(backupId, lease, artifact)) {
                    throw new AdminJobLeaseLostException();
                }
                return null;
            });
        } catch (RuntimeException failure) {
            if (partial != null) {
                try {
                    fileStore.deleteAttempt(partial);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            markFailedIfOwned(context, backupId, lease, failure);
            throw failure;
        }
    }

    private void validateArtifact(Long backupId) {
        DatabaseBackupRecord backup = requireBackup(backupId);
        if (!backup.status().isDownloadable() || backup.storageKey() == null
                || backup.sizeBytes() == null || backup.sha256() == null) {
            throw conflict("Backup cannot be validated in its current state");
        }
        StoredBackupArtifact actual = fileStore.inspect(backup.storageKey());
        if (actual.sizeBytes() != backup.sizeBytes()
                || !actual.sha256().equals(backup.sha256())) {
            throw conflict("Backup integrity check failed");
        }
        process.validate(backup.storageKey());
    }

    private void markValidationFailed(Long backupId, RuntimeException failure) {
        try {
            repository.markValidationFailed(backupId);
        } catch (RuntimeException stateFailure) {
            failure.addSuppressed(stateFailure);
        }
    }

/** 在不持有连接的情况下记录无事务校验阶段的失败。 */
    private void recordFailureAudit(AdminAuditEvent event, RuntimeException failure) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.audited(event, () -> {
                throw failure;
            });
        } catch (RuntimeException recordedFailure) {
            if (recordedFailure != failure) {
                failure.addSuppressed(recordedFailure);
            }
        }
    }

    private void ensureDumpCapability() {
        if (!process.dumpAvailable() || !fileStore.isAvailable()) {
            throw capabilityUnavailable();
        }
    }

    private void ensurePreDumpCapacity() {
        long databaseSize = repository.databaseSizeBytes();
        long estimatedDumpSize = saturatingAdd(databaseSize, ceilQuarter(databaseSize));
        long required = saturatingAdd(
                estimatedDumpSize, properties.minFreeBytes());
        long projectedTotal = saturatingAdd(
                fileStore.totalSizeBytes(), estimatedDumpSize);
        if (fileStore.usableSpaceBytes() < required
                || projectedTotal > properties.totalQuotaBytes()) {
            throw capacityExceeded();
        }
    }

    private void ensurePostDumpCapacity(long attemptSize) {
        if (attemptSize < 0
                || fileStore.usableSpaceBytes() < properties.minFreeBytes()
                || fileStore.totalSizeBytes() > properties.totalQuotaBytes()) {
            throw capacityExceeded();
        }
    }

    private long ceilQuarter(long value) {
        if (value < 0) {
            return Long.MAX_VALUE;
        }
        return value / 4 + (value % 4 == 0 ? 0 : 1);
    }

    private long saturatingAdd(long left, long right) {
        if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void markFailedIfOwned(
            AdminJobExecutionContext context,
            Long backupId,
            AdminJobLease lease,
            RuntimeException failure
    ) {
        try {
            context.inLeaseTransaction(operations -> {
                if (!repository.markFailed(backupId, lease, "Backup creation failed")) {
                    throw new AdminJobLeaseLostException();
                }
                return null;
            });
        } catch (RuntimeException stateFailure) {
            failure.addSuppressed(stateFailure);
        }
    }

    private DatabaseBackupRecord requireBackup(Long backupId) {
        requirePositive(backupId);
        return repository.findById(backupId).orElseThrow(this::backupNotFound);
    }

    private BusinessException backupNotFound() {
        return new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "Database backup not found");
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private void requirePositive(Long backupId) {
        if (backupId == null || backupId <= 0) {
            throw validation("Backup id must be positive");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private BusinessException capabilityUnavailable() {
        return new BusinessException(
                ErrorCode.BACKUP_CAPABILITY_UNAVAILABLE,
                "Database backup capability is unavailable");
    }

    private BusinessException capacityExceeded() {
        return new BusinessException(
                ErrorCode.BACKUP_CAPACITY_EXCEEDED,
                "Database backup capacity is insufficient");
    }

    private <T> T audited(AdminAuditEvent event, Supplier<T> operation) {
        return auditService == null ? operation.get() : auditService.audited(event, operation);
    }

    private <T> T auditedResult(
            AdminAuditEvent event,
            Supplier<T> operation,
            java.util.function.Function<T, AdminAuditEvent> success
    ) {
        return auditService == null
                ? operation.get()
                : auditService.auditedResult(event, operation, success);
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (java.io.IOException | RuntimeException ignored) {
// 保留最初的响应构造失败。
        }
    }
}

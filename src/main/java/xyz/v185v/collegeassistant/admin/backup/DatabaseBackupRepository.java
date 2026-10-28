package xyz.v185v.collegeassistant.admin.backup;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.v185v.collegeassistant.admin.backup.persistence.DatabaseBackupDO;
import xyz.v185v.collegeassistant.admin.backup.persistence.DatabaseBackupMapper;
import xyz.v185v.collegeassistant.admin.job.AdminJobLease;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

/** 使用固定查询持久化逻辑备份，并在租约保护下发布。 */
@Repository
public class DatabaseBackupRepository {

    private final DatabaseBackupMapper mapper;
    private final TransactionTemplate transactions;

    public DatabaseBackupRepository(
            DatabaseBackupMapper mapper,
            PlatformTransactionManager transactionManager
    ) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

/** 原子创建单例任务和备份记录。 */
    public DatabaseBackupRecord create(Long actorId, int retentionDays) {
        requirePositive(actorId, "actorId");
        if (retentionDays < 1 || retentionDays > 3_650) {
            throw new IllegalArgumentException("retentionDays is invalid");
        }
        try {
            return transactions.execute(status -> createInTransaction(actorId, retentionDays));
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, "23505")) {
                throw new BusinessException(
                        ErrorCode.BACKUP_ALREADY_RUNNING,
                        "A database backup is already running");
            }
            throw exception;
        }
    }

    public PageResult<DatabaseBackupRecord> findPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Backup pagination is invalid");
        }
        long total = mapper.selectCount(Wrappers.<DatabaseBackupDO>lambdaQuery());
        // Page 使用 long 偏移，仍保留原接口的 int 偏移溢出检查。
        Math.multiplyExact(page - 1, size);
        List<DatabaseBackupRecord> items = mapper.selectPage(new Page<DatabaseBackupDO>(page, size, false),
                        Wrappers.<DatabaseBackupDO>lambdaQuery()
                                .orderByDesc(DatabaseBackupDO::getCreatedAt, DatabaseBackupDO::getId))
                .getRecords().stream()
                .map(this::toRecord)
                .toList();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(List.copyOf(items), total, page, size, pages);
    }

    public Optional<DatabaseBackupRecord> findById(Long backupId) {
        requirePositive(backupId, "backupId");
        return Optional.ofNullable(mapper.selectById(backupId)).map(this::toRecord);
    }

    public long databaseSizeBytes() {
        return mapper.databaseSizeBytes();
    }

    public boolean markRunning(Long backupId, AdminJobLease lease) {
        return mapper.markRunning(
                backupId, lease.jobId(), lease.attempt(), lease.leaseToken()) == 1;
    }

    public boolean publish(
            Long backupId,
            AdminJobLease lease,
            StoredBackupArtifact artifact
    ) {
        return mapper.publishReturning(
                backupId, lease.jobId(), lease.attempt(), lease.leaseToken(),
                artifact.objectKey(), artifact.sha256(), artifact.sizeBytes()) != null;
    }

    public boolean markFailed(Long backupId, AdminJobLease lease, String summary) {
        return mapper.markFailed(
                backupId, lease.jobId(), lease.attempt(), lease.leaseToken(),
                safeSummary(summary)) == 1;
    }

    public DatabaseBackupRecord markValidated(Long backupId) {
        return Optional.ofNullable(mapper.markValidatedReturning(backupId))
                .map(this::toRecord)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONFLICT, "Backup cannot be validated in its current state"));
    }

    public DatabaseBackupRecord markValidationFailed(Long backupId) {
        return Optional.ofNullable(mapper.markValidationFailedReturning(backupId))
                .map(this::toRecord)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONFLICT, "Backup validation state changed"));
    }

/** 锁定两条记录，避免通用失败任务重试与破坏性删除竞争。 */
    public Optional<DatabaseBackupRecord> lockForDeletion(Long backupId) {
        requirePositive(backupId, "backupId");
        Optional<DatabaseBackupRecord> locked = Optional.ofNullable(mapper.lockForDeletion(backupId))
                .map(this::toRecord);
        if (locked.isEmpty()) {
            return locked;
        }
        DatabaseBackupRecord backup = locked.orElseThrow();
        if (backup.status().isActive()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "An active backup cannot be deleted");
        }
        if (backup.jobId() != null) {
            String jobStatus = mapper.findJobStatus(backup.jobId());
            if ("QUEUED".equals(jobStatus) || "RUNNING".equals(jobStatus)) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, "The backup job is active and cannot be deleted");
            }
        }
        return locked;
    }

    public boolean delete(Long backupId) {
        return mapper.delete(Wrappers.<DatabaseBackupDO>lambdaQuery()
                .eq(DatabaseBackupDO::getId, backupId)
                .notIn(DatabaseBackupDO::getStatus, "QUEUED", "RUNNING")) == 1;
    }

    public boolean tryAcquireRetentionLock() {
        return mapper.tryAcquireRetentionLock();
    }

    public int reconcileTerminalJobs() {
        return mapper.reconcileTerminalJobs();
    }

    public Optional<DatabaseBackupRecord> lockNextExpired() {
        return Optional.ofNullable(mapper.lockNextExpired()).map(this::toRecord);
    }

    public boolean deleteLocked(Long backupId) {
        return backupId != null && mapper.deleteById(backupId) == 1;
    }

    public boolean markDeleteFailed(Long backupId, String summary) {
        return mapper.markDeleteFailed(backupId, safeSummary(summary)) == 1;
    }

    public Set<String> referencedStorageKeys() {
        return Set.copyOf(new LinkedHashSet<>(mapper.selectList(Wrappers.<DatabaseBackupDO>lambdaQuery()
                        .select(DatabaseBackupDO::getStorageKey)
                        .isNotNull(DatabaseBackupDO::getStorageKey))
                .stream().map(DatabaseBackupDO::getStorageKey).toList()));
    }

    private DatabaseBackupRecord createInTransaction(Long actorId, int retentionDays) {
        Long jobId = mapper.insertJobReturningId(actorId);
        Long backupId = mapper.insertBackupReturningId(jobId, actorId, retentionDays);
        mapper.attachBackupToJob(jobId, backupId);
        return findById(backupId).orElseThrow();
    }

    private DatabaseBackupRecord toRecord(DatabaseBackupDO backup) {
        return new DatabaseBackupRecord(
                backup.getId(), backup.getJobId(),
                DatabaseBackupStatus.valueOf(backup.getStatus()), backup.getFormat(),
                backup.getStorageKey(), backup.getSha256(), backup.getSizeBytes(),
                backup.getRequestedBy(), backup.getStartedAt(), backup.getFinishedAt(),
                backup.getRetentionDays(), backup.getExpiresAt(), backup.getErrorSummary(),
                backup.getCreatedAt(), backup.getUpdatedAt());
    }

    private boolean hasSqlState(Throwable failure, String state) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && state.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "Backup operation failed";
        }
        String safe = summary.strip();
        return safe.length() <= 1_000 ? safe : safe.substring(0, 1_000);
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

package xyz.v185v.collegeassistant.admin.backup;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 通过咨询锁串行执行过期与孤儿清理。 */
@Component
public class BackupRetentionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupRetentionScheduler.class);

    private final DatabaseBackupRepository repository;
    private final BackupFileStore fileStore;
    private final TransactionOperations transactions;
    private final BackupProperties properties;

    @Autowired
    public BackupRetentionScheduler(
            DatabaseBackupRepository repository,
            BackupFileStore fileStore,
            PlatformTransactionManager transactionManager,
            BackupProperties properties
    ) {
        this(repository, fileStore, new TransactionTemplate(transactionManager), properties);
    }

    BackupRetentionScheduler(
            DatabaseBackupRepository repository,
            BackupFileStore fileStore,
            TransactionOperations transactions,
            BackupProperties properties
    ) {
        this.repository = repository;
        this.fileStore = fileStore;
        this.transactions = transactions;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${app.backup.retention-interval:PT1H}",
            fixedDelayString = "${app.backup.retention-interval:PT1H}"
    )
    public void scheduledCleanup() {
        try {
            cleanupExpired();
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Backup retention cleanup failed: causeType={}",
                    failure.getClass().getName());
        }
    }

/** 每个事务最多删除一行，并在首次可重试失败后停止。 */
    public int cleanupExpired() {
        int deleted = 0;
        while (true) {
            CleanupOutcome outcome = transactions.execute(status -> cleanupOne());
            if (outcome == CleanupOutcome.DELETED) {
                deleted++;
                continue;
            }
            return deleted;
        }
    }

    private CleanupOutcome cleanupOne() {
        if (!repository.tryAcquireRetentionLock()) {
            return CleanupOutcome.LOCK_BUSY;
        }
        repository.reconcileTerminalJobs();
        Optional<DatabaseBackupRecord> candidate = repository.lockNextExpired();
        if (candidate.isEmpty()) {
            cleanupOrphans();
            return CleanupOutcome.EMPTY;
        }
        DatabaseBackupRecord backup = candidate.orElseThrow();
        try {
            if (backup.storageKey() != null) {
                fileStore.delete(backup.storageKey());
            }
            if (!repository.deleteLocked(backup.id())) {
                throw new IllegalStateException("Backup row changed during retention cleanup");
            }
            return CleanupOutcome.DELETED;
        } catch (RuntimeException failure) {
            repository.markDeleteFailed(backup.id(), "Backup deletion failed");
            return CleanupOutcome.DELETE_FAILED;
        }
    }

    private void cleanupOrphans() {
        Set<String> referenced = repository.referencedStorageKeys();
        Instant cutoff = Instant.now()
                .minus(properties.dumpTimeout())
                .minus(properties.killGrace());
        fileStore.cleanupOrphans(referenced, cutoff);
    }

    private enum CleanupOutcome {
        DELETED,
        DELETE_FAILED,
        EMPTY,
        LOCK_BUSY
    }
}

package xyz.v185v.collegeassistant.admin.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

class BackupRetentionSchedulerTest {

    @TempDir
    Path tempDir;

    private DatabaseBackupRepository repository;
    private BackupFileStore fileStore;
    private BackupRetentionScheduler scheduler;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        repository = mock(DatabaseBackupRepository.class);
        fileStore = mock(BackupFileStore.class);
        TransactionOperations transactions = mock(TransactionOperations.class);
        when(transactions.execute(any())).thenAnswer(invocation -> invocation
                .<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)));
        scheduler = new BackupRetentionScheduler(
                repository, fileStore, transactions, properties());
        when(repository.tryAcquireRetentionLock()).thenReturn(true);
    }

    @Test
    void deletesExpiredArchivesOneAtATimeUnderTheDatabaseLock() {
        DatabaseBackupRecord expired = expiredRecord(DatabaseBackupStatus.VALIDATED);
        when(repository.lockNextExpired()).thenReturn(Optional.of(expired), Optional.empty());
        when(repository.deleteLocked(expired.id())).thenReturn(true);

        int deleted = scheduler.cleanupExpired();

        assertThat(deleted).isEqualTo(1);
        verify(fileStore).delete(expired.storageKey());
        verify(repository).deleteLocked(expired.id());
        verify(repository, never()).markDeleteFailed(any(Long.class), anyString());
    }

    @Test
    void failedFileDeletionPreservesARetryableDeleteFailedRow() {
        DatabaseBackupRecord expired = expiredRecord(DatabaseBackupStatus.SUCCEEDED);
        when(repository.lockNextExpired()).thenReturn(Optional.of(expired));
        doThrow(new IllegalStateException("sensitive path"))
                .when(fileStore).delete(expired.storageKey());

        int deleted = scheduler.cleanupExpired();

        assertThat(deleted).isZero();
        verify(repository).markDeleteFailed(expired.id(), "Backup deletion failed");
        verify(repository, never()).deleteLocked(expired.id());
    }

    @Test
    void anotherNodeHoldingTheAdvisoryLockMakesCleanupANoOp() {
        when(repository.tryAcquireRetentionLock()).thenReturn(false);

        assertThat(scheduler.cleanupExpired()).isZero();

        verify(repository, never()).lockNextExpired();
        verify(fileStore, never()).delete(anyString());
    }

    private DatabaseBackupRecord expiredRecord(DatabaseBackupStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new DatabaseBackupRecord(
                71L, 81L, status, "CUSTOM", "opaque.dump", "a".repeat(64), 300L,
                1L, now.minusDays(8), now.minusDays(8), 7, now.minusDays(1),
                null, now.minusDays(8), now.minusDays(8));
    }

    private BackupProperties properties() {
        return new BackupProperties(
                tempDir.resolve("backups"), tempDir.resolve("pg-dump"),
                tempDir.resolve("pg-restore"), "db", 5432, "college", "user", "password",
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1),
                1024, 256, 10_000, 7, Duration.ofHours(1));
    }
}

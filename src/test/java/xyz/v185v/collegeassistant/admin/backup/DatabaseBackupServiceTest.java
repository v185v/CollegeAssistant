package xyz.v185v.collegeassistant.admin.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobLease;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.job.LeaseGuardedJobOperations;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class DatabaseBackupServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-26T00:00:00Z");
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());

    @TempDir
    Path tempDir;

    private DatabaseBackupRepository repository;
    private PgBackupProcess process;
    private BackupFileStore fileStore;
    private BackupRetentionScheduler retention;
    private BackupProperties properties;
    private DatabaseBackupService service;

    @BeforeEach
    void setUp() {
        repository = mock(DatabaseBackupRepository.class);
        process = mock(PgBackupProcess.class);
        fileStore = mock(BackupFileStore.class);
        retention = mock(BackupRetentionScheduler.class);
        properties = properties();
        service = new DatabaseBackupService(
                repository, process, fileStore, retention, properties);
    }

    @Test
    void createCleansExpiredRowsChecksCapacityAndPersistsOneDurableWinner() {
        allowDumpCapacity();
        when(repository.create(ADMIN.userId(), 14)).thenReturn(record(
                DatabaseBackupStatus.QUEUED, null, null, null));

        DatabaseBackupDto created = service.create(ADMIN, 14);

        assertThat(created.id()).isEqualTo(71L);
        assertThat(created.status()).isEqualTo(DatabaseBackupStatus.QUEUED);
        InOrder order = inOrder(retention, repository);
        order.verify(retention).cleanupExpired();
        order.verify(repository).databaseSizeBytes();
        order.verify(repository).create(ADMIN.userId(), 14);
        verify(process, never()).dump(any());
    }

    @Test
    void createMapsUnavailableTrustedExecutableToTheDedicatedCapabilityCode() {
        when(process.dumpAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.create(ADMIN, 7))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.BACKUP_CAPABILITY_UNAVAILABLE));

        verify(repository, never()).create(anyLong(), anyInt());
    }

    @Test
    void createRejectsInsufficientUsableSpaceBeforePersistingAJob() {
        when(process.dumpAvailable()).thenReturn(true);
        when(fileStore.isAvailable()).thenReturn(true);
        when(repository.databaseSizeBytes()).thenReturn(1_000L);
        when(fileStore.usableSpaceBytes()).thenReturn(1_400L);
        when(fileStore.totalSizeBytes()).thenReturn(0L);

        assertThatThrownBy(() -> service.create(ADMIN, 7))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.BACKUP_CAPACITY_EXCEEDED));

        verify(repository, never()).create(anyLong(), anyInt());
    }

    @Test
    void createRejectsAnEstimatedDumpThatCannotFitInsideTheTotalQuota() {
        when(process.dumpAvailable()).thenReturn(true);
        when(fileStore.isAvailable()).thenReturn(true);
        when(repository.databaseSizeBytes()).thenReturn(1_000L);
        when(fileStore.usableSpaceBytes()).thenReturn(10_000L);
        when(fileStore.totalSizeBytes()).thenReturn(9_500L);

        assertThatThrownBy(() -> service.create(ADMIN, 7))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.BACKUP_CAPACITY_EXCEEDED));

        verify(repository, never()).create(anyLong(), anyInt());
    }

    @Test
    void createPreflightFailureStillEmitsAFailureAuditWithoutLaunchingAProcess() {
        AdminAuditService auditService = mock(AdminAuditService.class);
        DatabaseBackupService auditedService = new DatabaseBackupService(
                repository, process, fileStore, retention, properties, auditService);
        when(process.dumpAvailable()).thenReturn(false);
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                invocation.<Supplier<Object>>getArgument(1).get());

        assertThatThrownBy(() -> auditedService.create(ADMIN, 7))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.BACKUP_CAPABILITY_UNAVAILABLE));

        verify(auditService).audited(any(), any());
        verify(process, never()).dump(any());
    }

    @Test
    void handlerPublishesOnlyAfterDumpCapacityHashAndLeaseGuardedPromotion() {
        allowDumpCapacity();
        AdminJobExecutionContext context = context();
        AdminJobLease lease = context.lease();
        Path partial = tempDir.resolve("attempt.partial");
        StoredBackupArtifact artifact = new StoredBackupArtifact(
                "backup-job-81-attempt-0-lease-" + lease.leaseToken() + ".dump",
                300,
                "a".repeat(64));
        when(repository.markRunning(71L, lease)).thenReturn(true);
        when(process.dump(lease)).thenReturn(partial);
        when(fileStore.attemptSize(partial, lease)).thenReturn(300L);
        when(fileStore.promoteAttemptAtomically(partial, lease)).thenReturn(artifact);
        when(repository.publish(71L, lease, artifact)).thenReturn(true);

        service.execute(context);

        InOrder order = inOrder(repository, process, fileStore);
        order.verify(repository).markRunning(71L, lease);
        order.verify(process).dump(lease);
        order.verify(fileStore).attemptSize(partial, lease);
        order.verify(fileStore).promoteAttemptAtomically(partial, lease);
        order.verify(repository).publish(71L, lease, artifact);
    }

    @Test
    void validationChecksStoredSizeAndChecksumBeforeInvokingPgRestore() {
        StoredBackupArtifact artifact = new StoredBackupArtifact(
                "opaque.dump", 300, "b".repeat(64));
        when(process.restoreAvailable()).thenReturn(true);
        when(fileStore.isAvailable()).thenReturn(true);
        when(repository.findById(71L)).thenReturn(java.util.Optional.of(record(
                DatabaseBackupStatus.SUCCEEDED,
                artifact.objectKey(), artifact.sizeBytes(), artifact.sha256())));
        when(fileStore.inspect(artifact.objectKey())).thenReturn(artifact);
        when(repository.markValidated(71L)).thenReturn(record(
                DatabaseBackupStatus.VALIDATED,
                artifact.objectKey(), artifact.sizeBytes(), artifact.sha256()));

        DatabaseBackupDto validated = service.validate(ADMIN, 71L);

        assertThat(validated.status()).isEqualTo(DatabaseBackupStatus.VALIDATED);
        verify(process).validate(artifact.objectKey());
    }

    @Test
    void validationRunsPgRestoreBeforeOpeningTheShortAuditTransaction() {
        StoredBackupArtifact artifact = new StoredBackupArtifact(
                "opaque.dump", 300, "d".repeat(64));
        AdminAuditService auditService = mock(AdminAuditService.class);
        DatabaseBackupService auditedService = new DatabaseBackupService(
                repository, process, fileStore, retention, properties, auditService);
        when(process.restoreAvailable()).thenReturn(true);
        when(fileStore.isAvailable()).thenReturn(true);
        when(repository.findById(71L)).thenReturn(java.util.Optional.of(record(
                DatabaseBackupStatus.SUCCEEDED,
                artifact.objectKey(), artifact.sizeBytes(), artifact.sha256())));
        when(fileStore.inspect(artifact.objectKey())).thenReturn(artifact);
        when(repository.markValidated(71L)).thenReturn(record(
                DatabaseBackupStatus.VALIDATED,
                artifact.objectKey(), artifact.sizeBytes(), artifact.sha256()));
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                invocation.<Supplier<DatabaseBackupRecord>>getArgument(1).get());

        auditedService.validate(ADMIN, 71L);

        InOrder order = inOrder(process, auditService);
        order.verify(process).validate(artifact.objectKey());
        order.verify(auditService).audited(any(), any());
    }

    @Test
    void validationCapabilityFailureIsAuditedWithoutChangingTheArchiveState() {
        AdminAuditService auditService = mock(AdminAuditService.class);
        DatabaseBackupService auditedService = new DatabaseBackupService(
                repository, process, fileStore, retention, properties, auditService);
        when(process.restoreAvailable()).thenReturn(false);
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                invocation.<Supplier<Object>>getArgument(1).get());

        assertThatThrownBy(() -> auditedService.validate(ADMIN, 71L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.BACKUP_CAPABILITY_UNAVAILABLE));

        verify(auditService).audited(any(), any());
        verify(repository, never()).markValidationFailed(anyLong());
    }

    @Test
    void deleteLocksTheBackupAndJobBeforeRemovingTheImmutableFile() {
        StoredBackupArtifact artifact = new StoredBackupArtifact(
                "opaque.dump", 300, "e".repeat(64));
        when(repository.lockForDeletion(71L)).thenReturn(java.util.Optional.of(record(
                DatabaseBackupStatus.SUCCEEDED,
                artifact.objectKey(), artifact.sizeBytes(), artifact.sha256())));
        when(repository.delete(71L)).thenReturn(true);

        service.delete(ADMIN, 71L);

        InOrder order = inOrder(repository, fileStore);
        order.verify(repository).lockForDeletion(71L);
        order.verify(fileStore).delete(artifact.objectKey());
        order.verify(repository).delete(71L);
    }

    @Test
    void deleteConflictBeforeFilesystemWorkDoesNotOverwriteTheBackupStatus() {
        when(repository.lockForDeletion(71L)).thenThrow(
                new BusinessException(ErrorCode.CONFLICT, "Backup job is active"));

        assertThatThrownBy(() -> service.delete(ADMIN, 71L))
                .isInstanceOf(BusinessException.class);

        verify(fileStore, never()).delete(any());
        verify(repository, never()).markDeleteFailed(anyLong(), any());
    }

    @Test
    void everyPublicOperationRequiresAnAdministratorAtTheServiceBoundary() {
        AuthPrincipal student = new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

        assertThatThrownBy(() -> service.create(student, 7))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ADMIN_REQUIRED));
        assertThatThrownBy(() -> service.list(student, 1, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.validate(student, 71L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.openDownload(student, 71L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(student, 71L))
                .isInstanceOf(BusinessException.class);
    }

    private void allowDumpCapacity() {
        when(process.dumpAvailable()).thenReturn(true);
        when(fileStore.isAvailable()).thenReturn(true);
        when(repository.databaseSizeBytes()).thenReturn(1_000L);
        when(fileStore.usableSpaceBytes()).thenReturn(10_000L);
        when(fileStore.totalSizeBytes()).thenReturn(100L);
    }

    @SuppressWarnings("unchecked")
    private AdminJobExecutionContext context() {
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        AdminJobLease lease = new AdminJobLease(81L, 0, "worker", UUID.randomUUID());
        when(context.lease()).thenReturn(lease);
        when(context.job()).thenReturn(new AdminJobDto(
                81L, AdminJobType.DATABASE_BACKUP, AdminJobStatus.RUNNING,
                "DATABASE_BACKUP", 71L, ADMIN.userId(), 0, "worker", lease.leaseToken(),
                NOW, NOW, NOW, null, null, null, Map.of("backupId", 71L), NOW, NOW,
                List.of()));
        when(context.inLeaseTransaction(any())).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(mock(LeaseGuardedJobOperations.class));
        });
        return context;
    }

    private DatabaseBackupRecord record(
            DatabaseBackupStatus status,
            String storageKey,
            Long size,
            String sha256
    ) {
        return new DatabaseBackupRecord(
                71L, 81L, status, "CUSTOM", storageKey, sha256, size,
                ADMIN.userId(), null, null, 14, null, null, NOW, NOW);
    }

    private BackupProperties properties() {
        return new BackupProperties(
                tempDir.resolve("backups"),
                tempDir.resolve("pg-dump"),
                tempDir.resolve("pg-restore"),
                "db.internal", 55432, "college", "backup_user", "password",
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1),
                1024, 256, 10_000, 7, Duration.ofHours(1));
    }
}

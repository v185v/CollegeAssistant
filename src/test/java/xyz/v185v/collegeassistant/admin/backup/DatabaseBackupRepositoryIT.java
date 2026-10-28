package xyz.v185v.collegeassistant.admin.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class DatabaseBackupRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private DatabaseBackupRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void concurrentCreateTransactionsCommitExactlyOneBackupAndOneJob() throws Exception {
        long firstAdmin = insertAdmin("backup-admin-a");
        long secondAdmin = insertAdmin("backup-admin-b");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Object> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> createAfterBarrier(firstAdmin, ready, start)),
                    executor.submit(() -> createAfterBarrier(secondAdmin, ready, start)));
            ready.await();
            start.countDown();
            for (Future<Object> future : futures) {
                outcomes.add(future.get());
            }
        }

        assertThat(outcomes).filteredOn(DatabaseBackupRecord.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(Integer.class::isInstance)
                .containsExactly(ErrorCode.BACKUP_ALREADY_RUNNING);
        assertThat(jdbcClient.sql("SELECT count(*) FROM database_backup")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM admin_job
                        WHERE job_type = 'DATABASE_BACKUP' AND status = 'QUEUED'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM database_backup backup
                        JOIN admin_job job ON job.id = backup.job_id
                        WHERE job.target_type = 'DATABASE_BACKUP'
                          AND job.target_id = backup.id
                        """).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void publishRequiresTheExactRunningAttemptAndLeaseToken() {
        long adminId = insertAdmin("backup-publish-admin");
        DatabaseBackupRecord created = repository.create(adminId, 7);
        var lease = jdbcClient.sql("""
                        UPDATE admin_job
                        SET status = 'RUNNING', worker_id = 'worker',
                            lease_token = gen_random_uuid(), heartbeat_at = now(),
                            started_at = now()
                        WHERE id = :jobId
                        RETURNING id, attempt, worker_id, lease_token
                        """)
                .param("jobId", created.jobId())
                .query((rs, row) -> new xyz.v185v.collegeassistant.admin.job.AdminJobLease(
                        rs.getLong("id"), rs.getInt("attempt"), rs.getString("worker_id"),
                        rs.getObject("lease_token", java.util.UUID.class)))
                .single();
        StoredBackupArtifact artifact = new StoredBackupArtifact(
                "backup-job-" + created.jobId() + "-attempt-0-lease-"
                        + lease.leaseToken() + ".dump",
                3,
                "c".repeat(64));

        assertThat(repository.markRunning(created.id(), lease)).isTrue();
        assertThat(repository.publish(created.id(), lease, artifact)).isTrue();
        assertThat(repository.findById(created.id()).orElseThrow().status())
                .isEqualTo(DatabaseBackupStatus.SUCCEEDED);
    }

    @Test
    void aRetriedFailedBackupCanEnterRunningWithOnlyItsNewLease() {
        long adminId = insertAdmin("backup-retry-admin");
        DatabaseBackupRecord created = repository.create(adminId, 7);
        UUID leaseToken = UUID.randomUUID();
        jdbcClient.sql("""
                        UPDATE database_backup
                        SET status = 'FAILED', finished_at = now(), expires_at = now(),
                            error_summary = 'Old attempt failed'
                        WHERE id = :backupId
                        """)
                .param("backupId", created.id())
                .update();
        jdbcClient.sql("""
                        UPDATE admin_job
                        SET status = 'RUNNING', attempt = 1, worker_id = 'retry-worker',
                            lease_token = CAST(:leaseToken AS uuid), heartbeat_at = now(),
                            started_at = now()
                        WHERE id = :jobId
                        """)
                .param("leaseToken", leaseToken.toString())
                .param("jobId", created.jobId())
                .update();
        var lease = new xyz.v185v.collegeassistant.admin.job.AdminJobLease(
                created.jobId(), 1, "retry-worker", leaseToken);

        assertThat(repository.markRunning(created.id(), lease)).isTrue();

        DatabaseBackupRecord running = repository.findById(created.id()).orElseThrow();
        assertThat(running.status()).isEqualTo(DatabaseBackupStatus.RUNNING);
        assertThat(running.finishedAt()).isNull();
        assertThat(running.expiresAt()).isNull();
        assertThat(running.errorSummary()).isNull();
    }

    @Test
    void deletionLockRejectsATerminalBackupWhoseGenericJobRetryIsActive() {
        long adminId = insertAdmin("backup-delete-lock-admin");
        DatabaseBackupRecord created = repository.create(adminId, 7);
        jdbcClient.sql("""
                        UPDATE database_backup
                        SET status = 'FAILED', error_summary = 'Old attempt failed'
                        WHERE id = :backupId
                        """)
                .param("backupId", created.id())
                .update();

        assertThatThrownBy(() -> repository.lockForDeletion(created.id()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void paginationKeysAndConditionalDeletesPreserveTheirContracts() {
        assertThat(repository.findPage(1, 2).pages()).isZero();
        long adminId = insertAdmin("backup-list-admin");
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Long id = jdbcClient.sql("""
                            INSERT INTO database_backup (requested_by, status, storage_key, created_at)
                            VALUES (:adminId, 'FAILED', :storageKey, '2026-10-01T00:00:00Z')
                            RETURNING id
                            """)
                    .param("adminId", adminId)
                    .param("storageKey", index == 2 ? null : "same.dump")
                    .query(Long.class).single();
            ids.add(id);
        }
        var first = repository.findPage(1, 2);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.pages()).isEqualTo(2);
        assertThat(first.items()).extracting(DatabaseBackupRecord::id)
                .containsExactly(ids.get(2), ids.get(1));
        var beyond = repository.findPage(3, 2);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(3);
        assertThat(beyond.page()).isEqualTo(3);
        assertThatThrownBy(() -> repository.findPage(Integer.MAX_VALUE, 100))
                .isInstanceOf(ArithmeticException.class);
        assertThat(repository.referencedStorageKeys()).containsExactly("same.dump");
        assertThat(repository.delete(ids.getFirst())).isTrue();
        assertThat(repository.delete(ids.getFirst())).isFalse();
        assertThat(repository.deleteLocked(ids.get(1))).isTrue();
        assertThat(repository.deleteLocked(ids.get(1))).isFalse();
        assertThat(repository.deleteLocked(null)).isFalse();
        DatabaseBackupRecord active = repository.create(adminId, 7);
        assertThat(repository.delete(active.id())).isFalse();
    }

    private Object createAfterBarrier(
            long actorId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return repository.create(actorId, 7);
        } catch (BusinessException exception) {
            return exception.getCode();
        }
    }

    private long insertAdmin(String username) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, 'hash', 'Backup administrator', 'ADMIN')
                        RETURNING id
                        """)
                .param("username", username)
                .query(Long.class)
                .single();
    }
}

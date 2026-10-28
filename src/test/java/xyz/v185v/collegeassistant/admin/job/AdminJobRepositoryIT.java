package xyz.v185v.collegeassistant.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class AdminJobRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private AdminJobRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void claimCheckpointFailureAndManualRetryFenceTheOldToken() {
        AdminJobDto created = repository.create(
                AdminJobType.DOCUMENT_REINDEX, "DOCUMENT", 41L, null,
                Map.of("reason", "refresh"));
        AdminJobLease first = repository.claimNext("worker-a").orElseThrow();
        AdminJobItemDto item = repository.upsertItem(
                first, "document:41", "DOCUMENT", "DOCUMENT", 41L).orElseThrow();

        assertThat(repository.checkpoint(
                first, item.id(), AdminJobStatus.SUCCEEDED, Map.of("stage", "vectors")))
                .isTrue();
        assertThat(repository.finish(
                first, AdminJobStatus.FAILED, "TEST_FAILURE", "Test failure"))
                .isTrue();

        AdminJobDto retried = repository.retryFailed(created.id());
        assertThat(retried.status()).isEqualTo(AdminJobStatus.QUEUED);
        assertThat(retried.attempt()).isEqualTo(1);
        assertThat(retried.items()).singleElement().satisfies(preserved -> {
            assertThat(preserved.status()).isEqualTo(AdminJobStatus.SUCCEEDED);
            assertThat(preserved.checkpoint()).containsEntry("stage", "vectors");
            assertThat(preserved.retryCount()).isZero();
        });

        AdminJobLease retry = repository.claimNext("worker-b").orElseThrow();
        assertThat(retry.jobId()).isEqualTo(first.jobId());
        assertThat(retry.attempt()).isEqualTo(1);
        assertThat(retry.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(repository.finish(first, AdminJobStatus.SUCCEEDED, null, null)).isFalse();
        assertThat(repository.heartbeat(first)).isFalse();
        assertThat(repository.finish(retry, AdminJobStatus.SUCCEEDED, null, null)).isTrue();
    }

    @Test
    void rebuildHistoryRetainsJsonLeaseFieldsAndUsesIdToBreakTimestampTies() {
        assertThat(repository.findLatestEmbeddingRebuild(41L)).isEmpty();
        var payload = Map.<String, Object>of("nested", Map.of("versions", List.of(1, 2)));
        AdminJobDto older = repository.create(
                AdminJobType.EMBEDDING_REBUILD, "EMBEDDING_CONFIGURATION", 41L, null, payload);
        AdminJobLease firstLease = repository.claimNext("rebuild-reader").orElseThrow();
        AdminJobDto running = repository.findById(older.id()).orElseThrow();
        assertThat(running.leaseToken()).isEqualTo(firstLease.leaseToken());
        assertThat(running.requestPayload()).isEqualTo(payload);
        assertThat(running.requestedBy()).isNull();
        assertThat(running.finishedAt()).isNull();
        assertThat(running.heartbeatAt()).isNotNull();
        assertThat(repository.finish(firstLease, AdminJobStatus.FAILED, "TEST", "retry")).isTrue();
        AdminJobDto newer = repository.create(
                AdminJobType.EMBEDDING_REBUILD, "EMBEDDING_CONFIGURATION", 41L, null, payload);
        repository.create(AdminJobType.DOCUMENT_REINDEX, "DOCUMENT", 41L, null, Map.of());
        jdbcClient.sql("UPDATE admin_job SET created_at = '2026-10-01T00:00:00Z'").update();
        assertThat(repository.findEmbeddingRebuilds()).extracting(AdminJobDto::id)
                .containsExactly(newer.id(), older.id());
        assertThat(repository.findLatestEmbeddingRebuild(41L)).hasValueSatisfying(job -> {
            assertThat(job.id()).isEqualTo(newer.id());
            assertThat(job.requestPayload()).isEqualTo(payload);
        });
        assertThat(repository.findLatestEmbeddingRebuild(42L)).isEmpty();
    }

    @Test
    void concurrentWorkersClaimOneQueuedRowAtMostOnce() throws Exception {
        AdminJobDto job = repository.create(
                AdminJobType.CONSISTENCY_SCAN, "DATABASE", null, null, Map.of());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<AdminJobLease>> first = pool.submit(() -> {
                ready.countDown();
                start.await();
                return repository.claimNext("worker-a");
            });
            Future<Optional<AdminJobLease>> second = pool.submit(() -> {
                ready.countDown();
                start.await();
                return repository.claimNext("worker-b");
            });
            ready.await();
            start.countDown();

            List<AdminJobLease> claimed = List.of(first.get(), second.get()).stream()
                    .flatMap(Optional::stream)
                    .toList();
            assertThat(claimed).singleElement()
                    .extracting(AdminJobLease::jobId)
                    .isEqualTo(job.id());
        }
    }

    @Test
    void staleLeaseExpiresAndActiveTargetUniquenessIsDatabaseBacked() {
        repository.create(AdminJobType.SOURCE_DELETE, "SOURCE", 8L, null, Map.of());
        assertThatThrownBy(() -> repository.create(
                AdminJobType.SOURCE_DELETE, "SOURCE", 8L, null, Map.of()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.CONFLICT));
        AdminJobLease lease = repository.claimNext("worker-a").orElseThrow();
        jdbcClient.sql("""
                        UPDATE admin_job
                        SET heartbeat_at = now() - interval '2 hours'
                        WHERE id = :jobId
                        """)
                .param("jobId", lease.jobId())
                .update();

        assertThat(repository.expireStale(Duration.ofHours(1))).isEqualTo(1);
        assertThat(repository.heartbeat(lease)).isFalse();
        assertThat(repository.findById(lease.jobId()).orElseThrow()).satisfies(expired -> {
            assertThat(expired.status()).isEqualTo(AdminJobStatus.FAILED);
            assertThat(expired.errorCode()).isEqualTo("LEASE_EXPIRED");
            assertThat(expired.leaseToken()).isNull();
        });
    }
}

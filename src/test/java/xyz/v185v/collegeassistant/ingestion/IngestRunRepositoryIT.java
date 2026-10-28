package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class IngestRunRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private IngestRunRepository repository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private IngestRunItemRepository itemRepository;

    @Test
    void startAndFinishPersistLifecycleCountsAndTimestamps() {
        SourceRecord source = createSource("Lifecycle");

        IngestRunDto running = repository.start(source.id(), "manual refresh");

        assertThat(running.id()).isPositive();
        assertThat(running.sourceId()).isEqualTo(source.id());
        assertThat(running.status()).isEqualTo(IngestRunStatus.RUNNING);
        assertThat(running.added()).isZero();
        assertThat(running.updated()).isZero();
        assertThat(running.skipped()).isZero();
        assertThat(running.failed()).isZero();
        assertThat(running.startedAt()).isNotNull();
        assertThat(running.finishedAt()).isNull();

        IngestRunDto finished = repository.finish(
                running.id(), IngestRunStatus.PARTIAL_SUCCESS, 2, 3, 4, 1, "one failed");

        assertThat(finished.status()).isEqualTo(IngestRunStatus.PARTIAL_SUCCESS);
        assertThat(finished.added()).isEqualTo(2);
        assertThat(finished.updated()).isEqualTo(3);
        assertThat(finished.skipped()).isEqualTo(4);
        assertThat(finished.failed()).isEqualTo(1);
        assertThat(finished.errorDetail()).isEqualTo("one failed");
        assertThat(finished.finishedAt()).isAfterOrEqualTo(finished.startedAt());
    }

    @Test
    void staleRunningLeaseIsFailedBeforeReplacementStarts() {
        SourceRecord source = createSource("Stale Lease");
        IngestRunDto stale = repository.start(source.id(), null);
        jdbcClient.sql("""
                        UPDATE ingest_run
                        SET heartbeat_at = now() - interval '2 hours'
                        WHERE id = :runId
                        """)
                .param("runId", stale.id())
                .update();

        assertThat(repository.expireStaleRunsForSource(source.id(), Duration.ofHours(1)))
                .singleElement()
                .extracting(IngestRunDto::id)
                .isEqualTo(stale.id());
        IngestRunDto replacement = repository.start(source.id(), "retry stale lease");

        assertThat(replacement.id()).isNotEqualTo(stale.id());
        assertThat(replacement.status()).isEqualTo(IngestRunStatus.RUNNING);
        assertThat(repository.heartbeat(stale.id())).isFalse();
        assertThat(repository.heartbeat(replacement.id())).isTrue();
        assertThat(repository.find(source.id(), IngestRunStatus.FAILED, 1, 10).items())
                .singleElement()
                .satisfies(expired -> {
                    assertThat(expired.id()).isEqualTo(stale.id());
                    assertThat(expired.errorDetail()).isEqualTo("lease expired");
                    assertThat(expired.finishedAt()).isNotNull();
                });
    }

    @Test
    void freshRunningLeaseConflictsWith40903() {
        SourceRecord source = createSource("Fresh Lease");
        IngestRunDto running = repository.start(source.id(), null);

        assertThatThrownBy(() -> repository.start(source.id(), "again"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_ALREADY_RUNNING));
        assertThat(repository.find(source.id(), IngestRunStatus.RUNNING, 1, 10).items())
                .extracting(IngestRunDto::id)
                .containsExactly(running.id());
    }

    @Test
    void retryConflictPreservesTheExistingPublicMessage() {
        SourceRecord source = createSource("Retry Conflict");
        IngestRunLease failed = repository.startLease(
                source.id(), "failed attempt", "MANUAL", "worker-a");
        assertThat(repository.finish(
                failed, IngestRunStatus.FAILED, 0, 0, 0, 1,
                "FETCH_FAILED", "fetch failed")).isTrue();
        repository.startLease(source.id(), "replacement", "MANUAL", "worker-b");

        assertThatThrownBy(() -> repository.retryFailed(failed.runId(), "worker-c"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_ALREADY_RUNNING);
                    assertThat(exception.getMessage()).isEqualTo(
                            "An active ingestion run already exists for this source");
                });
    }

    @Test
    void heartbeatExtendsRunningLease() {
        SourceRecord source = createSource("Heartbeat");
        IngestRunDto running = repository.start(source.id(), null);
        jdbcClient.sql("""
                        UPDATE ingest_run
                        SET heartbeat_at = now() - interval '2 hours'
                        WHERE id = :runId
                        """)
                .param("runId", running.id())
                .update();

        repository.heartbeat(running.id());

        assertThat(jdbcClient.sql("""
                        SELECT heartbeat_at >= now() - interval '1 minute'
                        FROM ingest_run
                        WHERE id = :runId
                        """)
                .param("runId", running.id())
                .query(Boolean.class)
                .single()).isTrue();
        assertThatThrownBy(() -> repository.start(source.id(), "after heartbeat"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_ALREADY_RUNNING));
    }

    @Test
    void exactLeaseCanDeferARunAndAStaleLeaseCannotMutateItAgain() {
        SourceRecord source = createSource("Deferred Lease");
        IngestRunLease lease = repository.startLease(
                source.id(), "remote throttled", "SCHEDULED", "crawler-a");
        OffsetDateTime retryAt = OffsetDateTime.parse("2026-07-25T12:30:00Z");

        assertThat(repository.defer(lease, retryAt)).isTrue();
        assertThat(repository.defer(lease, retryAt.plusMinutes(1))).isFalse();
        assertThat(repository.heartbeat(lease)).isFalse();
        assertThat(repository.findById(lease.runId())).hasValueSatisfying(run -> {
            assertThat(run.status()).isEqualTo(IngestRunStatus.DEFERRED);
            assertThat(run.errorDetail()).contains("deferred retry");
            assertThat(run.finishedAt()).isNull();
        });
        assertThat(jdbcClient.sql("""
                        SELECT worker_id IS NULL AND lease_token IS NULL
                        FROM ingest_run
                        WHERE id = :runId
                        """)
                .param("runId", lease.runId())
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void unrelatedDatabaseConstraintFailureIsNotTranslatedToRunningConflict() {
        assertThatThrownBy(() -> repository.start(Long.MAX_VALUE, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    void pagedHistoryFiltersBySourceAndStatusWithStableDescendingOrder() {
        SourceRecord firstSource = createSource("First");
        SourceRecord secondSource = createSource("Second");
        IngestRunDto older = finished(firstSource.id(), IngestRunStatus.SUCCESS);
        IngestRunDto middle = finished(firstSource.id(), IngestRunStatus.FAILED);
        IngestRunDto newer = finished(firstSource.id(), IngestRunStatus.SUCCESS);
        finished(secondSource.id(), IngestRunStatus.SUCCESS);
        jdbcClient.sql("UPDATE ingest_run SET started_at = '2026-07-14T00:00:00Z' WHERE source_id = :sourceId")
                .param("sourceId", firstSource.id())
                .update();

        PageResult<IngestRunDto> firstPage = repository.find(
                firstSource.id(), IngestRunStatus.SUCCESS, 1, 1);
        PageResult<IngestRunDto> secondPage = repository.find(
                firstSource.id(), IngestRunStatus.SUCCESS, 2, 1);

        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.pages()).isEqualTo(2);
        assertThat(firstPage.items()).extracting(IngestRunDto::id).containsExactly(newer.id());
        assertThat(secondPage.items()).extracting(IngestRunDto::id).containsExactly(older.id());
        assertThat(repository.find(firstSource.id(), IngestRunStatus.FAILED, 1, 10).items())
                .extracting(IngestRunDto::id)
                .containsExactly(middle.id());
        assertThat(repository.find(secondSource.id(), null, 1, 10).items()).hasSize(1);
        assertThat(repository.findLatestStatus(firstSource.id())).contains(IngestRunStatus.SUCCESS);
        assertThat(repository.hasActiveForSource(firstSource.id())).isFalse();
        var beyond = repository.find(firstSource.id(), IngestRunStatus.SUCCESS, Integer.MAX_VALUE, 100);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(2);
        assertThat(beyond.page()).isEqualTo(Integer.MAX_VALUE);
        jdbcClient.sql("UPDATE ingest_run SET trigger_type = 'IMPORT' WHERE source_id = :sourceId")
                .param("sourceId", firstSource.id()).update();
        var imports = repository.findImports(1, 2);
        assertThat(imports.total()).isEqualTo(3);
        assertThat(imports.pages()).isEqualTo(2);
        assertThat(imports.items()).extracting(IngestRunDto::id).containsExactly(newer.id(), middle.id());
        assertThat(repository.findImports(2, 2).items()).extracting(IngestRunDto::id)
                .containsExactly(older.id());
        assertThat(repository.findImports(3, 2).items()).isEmpty();
        assertThat(repository.deleteForSource(firstSource.id())).isEqualTo(3);
        assertThat(repository.deleteForSource(firstSource.id())).isZero();
        assertThat(repository.findLatestStatus(firstSource.id())).isEmpty();
        assertThat(repository.findById(newer.id())).isEmpty();
        assertThat(repository.find(secondSource.id(), null, 1, 10).total()).isEqualTo(1);
    }

    @Test
    void scheduledClaimCreatesOneDurableRunAndAdvancesNextRunAtomically() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        SourceRecord source = createScheduledSource("Claim", now.minusMinutes(1));

        List<ScheduledIngestClaim> claims = repository.claimDueScheduled(
                8, now, new SourceScheduleCalculator(), "scheduled-it");

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.source().id()).isEqualTo(source.id());
            assertThat(claim.running().status()).isEqualTo(IngestRunStatus.RUNNING);
            assertThat(claim.lease().attempt()).isZero();
        });
        assertThat(sourceRepository.findById(source.id())).hasValueSatisfying(updated ->
                assertThat(updated.nextRunAt()).isAfter(now));
        assertThat(jdbcClient.sql("""
                        SELECT trigger_type = 'SCHEDULED' AND scheduled_at = :scheduledAt
                        FROM ingest_run
                        WHERE source_id = :sourceId
                        """)
                .param("scheduledAt", source.nextRunAt())
                .param("sourceId", source.id())
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void disabledAndDeletingDueSourcesAreNotClaimedOrAdvanced() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        SourceRecord disabled = createScheduledSource("Disabled", now.minusMinutes(1));
        SourceRecord deleting = createScheduledSource("Deleting", now.minusMinutes(1));
        sourceRepository.setEnabled(disabled.id(), false);
        assertThat(sourceRepository.markDeleting(deleting.id())).isTrue();

        assertThat(repository.claimDueScheduled(
                8, now, new SourceScheduleCalculator(), "scheduled-it")).isEmpty();
        assertThat(countRuns(disabled.id())).isZero();
        assertThat(countRuns(deleting.id())).isZero();
        assertThat(sourceRepository.findById(deleting.id())).hasValueSatisfying(source ->
                assertThat(source.nextRunAt()).isEqualTo(deleting.nextRunAt()));
    }

    @Test
    void twoScheduledDispatchersClaimOneDueSourceOnlyOnce() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        SourceRecord source = createScheduledSource("Contention", now.minusMinutes(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<List<ScheduledIngestClaim>> first = pool.submit(racingClaim(
                    ready, start, () -> repository.claimDueScheduled(
                            1, now, new SourceScheduleCalculator(), "scheduled-a")));
            Future<List<ScheduledIngestClaim>> second = pool.submit(racingClaim(
                    ready, start, () -> repository.claimDueScheduled(
                            1, now, new SourceScheduleCalculator(), "scheduled-b")));
            ready.await();
            start.countDown();

            assertThat(first.get().size() + second.get().size()).isEqualTo(1);
        }
        assertThat(countRuns(source.id())).isEqualTo(1);
        assertThat(sourceRepository.findById(source.id())).hasValueSatisfying(updated ->
                assertThat(updated.nextRunAt()).isAfter(now));
    }

    @Test
    void twoDeferredDispatchersResumeTheSameRunOnlyOnce() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        SourceRecord source = createSource("Deferred Contention");
        IngestRunLease initialLease = repository.startLease(
                source.id(), "throttled", "SCHEDULED", "original-worker");
        assertThat(itemRepository.upsertItem(initialLease, "https://catalog.example.edu/deferred", "URL"))
                .isPresent();
        itemRepository.deferUnfinished(initialLease.runId(), now.minusSeconds(1));
        assertThat(repository.defer(initialLease, now.minusSeconds(1))).isTrue();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<List<ScheduledIngestClaim>> first = pool.submit(racingClaim(
                    ready, start, () -> repository.claimDueDeferred(1, now, "deferred-a")));
            Future<List<ScheduledIngestClaim>> second = pool.submit(racingClaim(
                    ready, start, () -> repository.claimDueDeferred(1, now, "deferred-b")));
            ready.await();
            start.countDown();

            assertThat(first.get().size() + second.get().size()).isEqualTo(1);
        }
        assertThat(repository.findDetailById(initialLease.runId())).hasValueSatisfying(run -> {
            assertThat(run.status()).isEqualTo(IngestRunStatus.RUNNING);
            assertThat(run.attempt()).isEqualTo(1);
            assertThat(run.items()).singleElement().satisfies(item ->
                    assertThat(item.status()).isEqualTo(IngestRunItemStatus.QUEUED));
            assertThat(run.items()).singleElement().satisfies(item ->
                    assertThat(item.nextAttemptAt()).isNull());
        });
        assertThat(countRuns(source.id())).isEqualTo(1);
    }

    private IngestRunDto finished(Long sourceId, IngestRunStatus status) {
        IngestRunDto run = repository.start(sourceId, null);
        long failed = status == IngestRunStatus.FAILED ? 1 : 0;
        return repository.finish(run.id(), status, status == IngestRunStatus.SUCCESS ? 1 : 0,
                0, 0, failed, failed == 0 ? null : "failed");
    }

    private SourceRecord createSource(String suffix) {
        return sourceRepository.create(new SourceRecord(
                null,
                "Ingest " + suffix,
                "https://" + suffix.toLowerCase(Locale.ROOT).replace(' ', '-')
                        + ".example.edu",
                "OFFICIAL",
                true,
                null,
                null));
    }

    private SourceRecord createScheduledSource(String suffix, OffsetDateTime nextRunAt) {
        return sourceRepository.create(new SourceRecord(
                null,
                "Scheduled " + suffix,
                "https://" + suffix.toLowerCase(Locale.ROOT).replace(' ', '-')
                        + ".example.edu",
                "OFFICIAL",
                true,
                SourceConnectorType.SINGLE_URL,
                "NONE",
                SourceScheduleType.DAILY,
                "Asia/Shanghai",
                "0 0 9 * * *",
                true,
                3,
                500,
                4,
                List.of(),
                List.of(),
                nextRunAt,
                null,
                null,
                null,
                null));
    }

    private Callable<List<ScheduledIngestClaim>> racingClaim(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<List<ScheduledIngestClaim>> operation
    ) {
        return () -> {
            ready.countDown();
            start.await();
            return operation.call();
        };
    }

    private long countRuns(Long sourceId) {
        return jdbcClient.sql("SELECT count(*) FROM ingest_run WHERE source_id = :sourceId")
                .param("sourceId", sourceId)
                .query(Long.class)
                .single();
    }
}

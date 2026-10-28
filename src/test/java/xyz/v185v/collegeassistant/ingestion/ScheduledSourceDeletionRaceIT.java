package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.admin.source.AdminSourceService;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceDto;
import xyz.v185v.collegeassistant.admin.source.SourceRequest;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class ScheduledSourceDeletionRaceIT extends DatabaseTestSupport {

    @Autowired
    private AdminSourceService sourceService;

    @Autowired
    private IngestRunRepository runRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @RepeatedTest(10)
    void scheduledClaimAndDeletionHaveOnlySerializedOutcomes() throws Exception {
        Long actorId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, role)
                        VALUES ('scheduled-race-admin', 'not-used', 'ADMIN')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        AuthPrincipal actor = new AuthPrincipal(actorId, "scheduled-race-admin", UserRole.ADMIN.name());
        SourceDto source = sourceService.create(actor, new SourceRequest(
                "Scheduled race", "OFFICIAL", SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu/scheduled-race", true, SourceScheduleType.DAILY,
                "Asia/Shanghai", "0 0 9 * * *", true, 3, 500, 4, List.of(), List.of()));
        OffsetDateTime claimAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        OffsetDateTime dueAt = claimAt.minusMinutes(1);
        jdbcClient.sql("UPDATE source SET next_run_at = :dueAt WHERE id = :sourceId")
                .param("dueAt", dueAt)
                .param("sourceId", source.id())
                .update();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<ScheduledAttempt> scheduled = pool.submit(racingScheduledAttempt(ready, start, () ->
                    runRepository.claimDueScheduled(
                            1, claimAt, new SourceScheduleCalculator(), "scheduled-race-worker")));
            Future<DeletionAttempt> deletion = pool.submit(racingDeletionAttempt(ready, start, () ->
                    sourceService.requestDeletion(actor, source.id())));
            ready.await();
            start.countDown();

            ScheduledAttempt scheduledResult = scheduled.get();
            DeletionAttempt deletionResult = deletion.get();
            if (scheduledResult.claimed()) {
                assertThat(deletionResult.succeeded()).isFalse();
                assertThat(deletionResult.errorCode()).isEqualTo(ErrorCode.INGEST_ALREADY_RUNNING);
                assertThat(sourceRepository.findById(source.id())).hasValueSatisfying(stored -> {
                    assertThat(stored.deletionStatus()).isEqualTo("NONE");
                    assertThat(stored.nextRunAt()).isAfter(claimAt);
                });
                assertThat(countRuns(source.id())).isEqualTo(1);
            } else {
                assertThat(deletionResult.succeeded()).isTrue();
                assertThat(sourceRepository.findById(source.id())).hasValueSatisfying(stored -> {
                    assertThat(stored.deletionStatus()).isEqualTo("DELETING");
                    assertThat(stored.nextRunAt()).isEqualTo(dueAt);
                });
                assertThat(countRuns(source.id())).isZero();
            }
        }
    }

    private Callable<ScheduledAttempt> racingScheduledAttempt(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<List<ScheduledIngestClaim>> operation
    ) {
        return () -> {
            ready.countDown();
            start.await();
            return new ScheduledAttempt(!operation.call().isEmpty());
        };
    }

    private Callable<DeletionAttempt> racingDeletionAttempt(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<?> operation
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                operation.call();
                return new DeletionAttempt(true, null);
            } catch (BusinessException exception) {
                return new DeletionAttempt(false, exception.getCode());
            }
        };
    }

    private long countRuns(Long sourceId) {
        return jdbcClient.sql("SELECT count(*) FROM ingest_run WHERE source_id = :sourceId")
                .param("sourceId", sourceId)
                .query(Long.class)
                .single();
    }

    private record ScheduledAttempt(boolean claimed) {
    }

    private record DeletionAttempt(boolean succeeded, Integer errorCode) {
    }
}

package xyz.v185v.collegeassistant.admin.document;

import static org.assertj.core.api.Assertions.assertThat;

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
import xyz.v185v.collegeassistant.ingestion.application.IngestionRunCoordinator;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class ImportSourceDeletionRaceIT extends DatabaseTestSupport {

    @Autowired
    AdminSourceService sourceService;

    @Autowired
    IngestionRunCoordinator coordinator;

    @Autowired
    JdbcClient jdbcClient;

    @RepeatedTest(10)
    void importCreationAndSourceDeletionHaveOnlyTwoSerializedOutcomes() throws Exception {
        Long actorId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, role)
                        VALUES ('import-race-admin', 'not-used', 'ADMIN')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        AuthPrincipal actor = new AuthPrincipal(
                actorId, "import-race-admin", UserRole.ADMIN.name());
        SourceDto source = sourceService.create(actor, new SourceRequest(
                "Upload race", "OFFICIAL", SourceConnectorType.LOCAL_UPLOAD,
                null, true, SourceScheduleType.MANUAL, "Asia/Shanghai", null,
                true, 3, 500, 4, List.of(), List.of()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Attempt> importing = pool.submit(racingAttempt(ready, start, () ->
                    coordinator.claimImport(
                            source.id(), "race", List.of("client-race"), "race-worker")));
            Future<Attempt> deleting = pool.submit(racingAttempt(ready, start, () ->
                    sourceService.requestDeletion(actor, source.id())));
            ready.await();
            start.countDown();

            Attempt importResult = importing.get();
            Attempt deleteResult = deleting.get();
            assertThat(List.of(importResult, deleteResult).stream().filter(Attempt::succeeded))
                    .hasSize(1);

            if (importResult.succeeded()) {
                assertThat(deleteResult.errorCode()).isEqualTo(ErrorCode.INGEST_ALREADY_RUNNING);
                assertThat(singleString(
                        "SELECT deletion_status FROM source WHERE id = :id", source.id()))
                        .isEqualTo("NONE");
                assertThat(count(
                        "SELECT count(*) FROM ingest_run WHERE source_id = :id", source.id()))
                        .isEqualTo(1);
                assertThat(count("""
                                SELECT count(*) FROM ingest_run_item item
                                JOIN ingest_run run ON run.id = item.run_id
                                WHERE run.source_id = :id AND item.item_kind = 'FILE'
                                """, source.id()))
                        .isEqualTo(1);
            } else {
                assertThat(deleteResult.succeeded()).isTrue();
                assertThat(importResult.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(singleString(
                        "SELECT deletion_status FROM source WHERE id = :id", source.id()))
                        .isEqualTo("DELETING");
                assertThat(count(
                        "SELECT count(*) FROM ingest_run WHERE source_id = :id", source.id()))
                        .isZero();
                assertThat(count("""
                                SELECT count(*) FROM admin_job
                                WHERE target_id = :id AND job_type = 'SOURCE_DELETE'
                                """, source.id()))
                        .isEqualTo(1);
            }
        }
    }

    private Callable<Attempt> racingAttempt(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<?> operation
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                operation.call();
                return Attempt.success();
            } catch (BusinessException exception) {
                return Attempt.failure(exception.getCode());
            }
        };
    }

    private long count(String sql, Long sourceId) {
        return jdbcClient.sql(sql).param("id", sourceId).query(Long.class).single();
    }

    private String singleString(String sql, Long sourceId) {
        return jdbcClient.sql(sql).param("id", sourceId).query(String.class).single();
    }

    private record Attempt(boolean succeeded, Integer errorCode) {
        private static Attempt success() {
            return new Attempt(true, null);
        }

        private static Attempt failure(Integer errorCode) {
            return new Attempt(false, errorCode);
        }
    }
}

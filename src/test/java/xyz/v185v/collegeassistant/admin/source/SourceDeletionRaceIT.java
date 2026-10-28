package xyz.v185v.collegeassistant.admin.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.application.IngestionRunCoordinator;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class SourceDeletionRaceIT extends DatabaseTestSupport {

    @Autowired
    private AdminSourceService sourceService;

    @Autowired
    private IngestionRunCoordinator runCoordinator;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void manualIngestionAndDeletionSerializeOnTheSourceMutationLock() throws Exception {
        Long actorId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, role)
                        VALUES ('race-admin', 'not-used', 'ADMIN')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        AuthPrincipal actor = new AuthPrincipal(actorId, "race-admin", UserRole.ADMIN.name());
        SourceDto source = sourceService.create(actor, new SourceRequest(
                "Race source", "OFFICIAL", SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu/race", true, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 3, 500, 4, List.of(), List.of()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Attempt> ingestion = pool.submit(racingAttempt(ready, start, () ->
                    runCoordinator.claimManual(source.id(), "race", "race-worker")));
            Future<Attempt> deletion = pool.submit(racingAttempt(ready, start, () ->
                    sourceService.requestDeletion(actor, source.id())));
            ready.await();
            start.countDown();

            Attempt ingestionResult = ingestion.get();
            Attempt deletionResult = deletion.get();
            assertThat(List.of(ingestionResult, deletionResult).stream()
                    .filter(Attempt::succeeded))
                    .hasSize(1);

            if (ingestionResult.succeeded()) {
                assertThat(deletionResult.errorCode()).isEqualTo(ErrorCode.INGEST_ALREADY_RUNNING);
                assertThat(singleString("SELECT deletion_status FROM source WHERE id = :id", source.id()))
                        .isEqualTo("NONE");
                assertThat(count("SELECT count(*) FROM ingest_run WHERE source_id = :id", source.id()))
                        .isEqualTo(1);
                assertThat(count("SELECT count(*) FROM admin_job WHERE target_id = :id", source.id()))
                        .isZero();
            } else {
                assertThat(deletionResult.succeeded()).isTrue();
                assertThat(ingestionResult.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(singleString("SELECT deletion_status FROM source WHERE id = :id", source.id()))
                        .isEqualTo("DELETING");
                assertThat(count("SELECT count(*) FROM ingest_run WHERE source_id = :id", source.id()))
                        .isZero();
                assertThat(count("""
                                SELECT count(*) FROM admin_job
                                WHERE target_id = :id AND job_type = 'SOURCE_DELETE' AND status = 'QUEUED'
                                """, source.id()))
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void failedDeletionCanBeReclaimedByARetriedJob() {
        Long actorId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, role)
                        VALUES ('recovery-admin', 'not-used', 'ADMIN')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        AuthPrincipal actor = new AuthPrincipal(actorId, "recovery-admin", UserRole.ADMIN.name());
        SourceDto source = sourceService.create(actor, new SourceRequest(
                "Recovery source", "OFFICIAL", SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu/recovery", true, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 3, 500, 4, List.of(), List.of()));

        assertThat(sourceRepository.markDeleting(source.id())).isTrue();
        assertThat(sourceRepository.markDeleteFailed(source.id())).isTrue();
        assertThat(sourceRepository.resumeDeleting(source.id())).isTrue();

        assertThat(singleString("SELECT deletion_status FROM source WHERE id = :id", source.id()))
                .isEqualTo("DELETING");
    }

    @Test
    void sourceCrudPersistsTheCompleteManagedConfiguration() {
        Long actorId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, role)
                        VALUES ('crud-admin', 'not-used', 'ADMIN')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        AuthPrincipal actor = new AuthPrincipal(actorId, "crud-admin", UserRole.ADMIN.name());
        SourceDto created = sourceService.create(actor, new SourceRequest(
                "Initial source", "OFFICIAL", SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu/initial", true, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 1, 25, 2, List.of("/plans/**"), List.of()));

        SourceDto updated = sourceService.update(actor, created.id(), new SourceRequest(
                "Managed site", "POLICY", SourceConnectorType.SITE,
                "https://catalog.example.edu/site", true, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, false, 5, 1000, 8,
                List.of("/site/**"), List.of("/site/archive/**")));
        SourceDto disabled = sourceService.setEnabled(actor, created.id(), false);

        assertThat(updated.name()).isEqualTo("Managed site");
        assertThat(updated.type()).isEqualTo("POLICY");
        assertThat(updated.connectorType()).isEqualTo(SourceConnectorType.SITE);
        assertThat(updated.includePatterns()).containsExactly("/site/**");
        assertThat(updated.excludePatterns()).containsExactly("/site/archive/**");
        assertThat(disabled.enabled()).isFalse();
        assertThat(sourceService.get(actor, created.id()).enabled()).isFalse();
        assertThat(sourceService.list(actor, 1, 20).items())
                .singleElement()
                .extracting(SourceDto::name)
                .isEqualTo("Managed site");
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

package xyz.v185v.collegeassistant.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.ingestion.application.IngestRunExpirationService;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AdminAuditIntegrationTest extends DatabaseTestSupport {

    @Autowired
    private AdminAuditService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminAuditRepository repository;

    @Autowired
    private AdminJobRepository jobRepository;

    @Autowired
    private AdminJobService jobService;

    @Autowired
    private IngestRunRepository runRepository;

    @Autowired
    private IngestRunExpirationService runExpirationService;

    @MockitoBean
    private AdminAuditWriter successWriter;

    @BeforeEach
    void resetSuccessWriter() {
        reset(successWriter);
    }

    @Test
    void successAuditFailureRollsBackTheDomainMutation() {
        long actorId = insertUser("audit-rollback-actor");
        long sourceId = insertSource("audit-rollback-source");
        DataIntegrityViolationException auditFailure =
                new DataIntegrityViolationException("audit unavailable");
        doThrow(auditFailure).when(successWriter).writeInCurrentTransaction(any());

        assertThatThrownBy(() -> service.audited(
                event(actorId, sourceId, Map.of("enabled", false)), () -> {
                    jdbcClient.sql("UPDATE source SET enabled = false WHERE id = :sourceId")
                            .param("sourceId", sourceId)
                            .update();
                    return null;
                }))
                .isSameAs(auditFailure);

        assertThat(jdbcClient.sql("SELECT enabled FROM source WHERE id = :sourceId")
                .param("sourceId", sourceId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(countAudits("SUCCEEDED")).isZero();
    }

    @Test
    void domainFailureCommitsOnlyTheFailureAuditInItsOwnTransaction() {
        long actorId = insertUser("audit-failure-actor");
        BusinessException failure = new BusinessException(ErrorCode.CONFLICT, "source is locked");

        assertThatThrownBy(() -> service.audited(
                event(actorId, 41L, Map.of("enabled", false)), () -> {
                    throw failure;
                }))
                .isSameAs(failure);

        assertThat(countAudits("SUCCEEDED")).isZero();
        assertThat(jdbcClient.sql("""
                        SELECT result, error_code, summary::text
                        FROM admin_audit_log
                        """)
                .query((resultSet, rowNum) -> List.of(
                        resultSet.getString("result"),
                        resultSet.getString("error_code"),
                        resultSet.getString("summary")))
                .list())
                .containsExactly(List.of("FAILED", String.valueOf(ErrorCode.CONFLICT),
                        "{\"enabled\": false}"));
    }

    @Test
    void filteredInventoryPreservesAllowedNullSummaryValues() {
        long actorId = insertUser("audit-filter-actor");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("optional", null);
        summary.put("enabled", true);
        repository.insert(event(actorId, 51L, summary));

        var result = service.list(
                new AuthPrincipal(actorId, "audit-filter-actor", UserRole.ADMIN.name()),
                new AdminAuditFilter(
                        actorId, "SOURCE_UPDATED", "SOURCE", 51L,
                        AdminAuditResult.SUCCEEDED, null, null),
                1,
                20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().summary())
                .containsEntry("enabled", true)
                .containsKey("optional");
        assertThat(result.items().getFirst().summary().get("optional")).isNull();
    }

    @Test
    void timeFilteredInventoryKeepsDescendingPaginationAndNetworkAddress() {
        long actorId = insertUser("audit-pagination-actor");
        OffsetDateTime firstCreatedAt = OffsetDateTime.parse("2026-01-01T01:00:00Z");
        OffsetDateTime secondCreatedAt = OffsetDateTime.parse("2026-01-01T02:00:00Z");
        OffsetDateTime thirdCreatedAt = OffsetDateTime.parse("2026-01-01T03:00:00Z");
        insertAuditAt(actorId, 61L, firstCreatedAt);
        insertAuditAt(actorId, 62L, secondCreatedAt);
        insertAuditAt(actorId, 63L, thirdCreatedAt);

        AuthPrincipal admin = new AuthPrincipal(
                actorId, "audit-pagination-actor", UserRole.ADMIN.name());
        AdminAuditFilter filter = new AdminAuditFilter(
                actorId, null, null, null, null, secondCreatedAt, thirdCreatedAt);

        var firstPage = service.list(admin, filter, 1, 1);
        var secondPage = service.list(admin, filter, 2, 1);

        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.pages()).isEqualTo(2);
        assertThat(firstPage.items()).extracting(AdminAuditLogDto::targetId)
                .containsExactly(63L);
        assertThat(firstPage.items().getFirst().remoteAddress()).isEqualTo("127.0.0.1/32");
        assertThat(secondPage.items()).extracting(AdminAuditLogDto::targetId)
                .containsExactly(62L);
    }

    @Test
    void addressProjectionAndEmptyFiltersKeepTheirDatabaseSemantics() {
        long actorId = insertUser("audit-address-actor");
        repository.insert(AdminAuditEvent.requested(actorId, "SOURCE_UPDATED", "SOURCE", 1L,
                Map.of("nested", Map.of("enabled", true)), "2001:db8::1"));
        repository.insert(AdminAuditEvent.requested(actorId, "SOURCE_UPDATED", "SOURCE", 2L,
                Map.of(), null));
        var filter = new AdminAuditFilter(actorId, null, null, null, null, null, null);
        var result = repository.find(filter, 1, 10);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(AdminAuditLogDto::remoteAddress)
                .containsExactly(null, "2001:db8::1/128");
        assertThat(result.items().getLast().summary())
                .containsEntry("nested", Map.of("enabled", true));
        assertThat(jdbcClient.sql("SELECT remote_address FROM admin_audit_log WHERE target_id = 1")
                .query((rs, row) -> rs.getString("remote_address")).single())
                .isEqualTo("2001:db8::1");
        var beyond = repository.find(filter, 2, 10);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(2);
        for (AdminAuditFilter emptyFilter : List.of(
                new AdminAuditFilter(actorId, "", null, null, null, null, null),
                new AdminAuditFilter(actorId, null, "", null, null, null, null))) {
            var empty = repository.find(emptyFilter, 1, 10);
            assertThat(empty.items()).isEmpty();
            assertThat(empty.total()).isZero();
        }
    }

    @Test
    void staleJobExpirationRollsBackWhenItsTerminalAuditFails() {
        long actorId = insertUser("audit-expiration-actor");
        var created = jobRepository.create(
                AdminJobType.CONSISTENCY_SCAN, "DATABASE", null, actorId, Map.of());
        var lease = jobRepository.claimNext("audit-expiration-worker").orElseThrow();
        jdbcClient.sql("""
                        UPDATE admin_job
                        SET heartbeat_at = now() - interval '2 hours'
                        WHERE id = :jobId
                        """)
                .param("jobId", created.id())
                .update();
        DataIntegrityViolationException auditFailure =
                new DataIntegrityViolationException("audit unavailable");
        doThrow(auditFailure).when(successWriter).writeInCurrentTransaction(any());

        assertThatThrownBy(() -> jobService.expireStale(Duration.ofHours(1)))
                .isSameAs(auditFailure);

        assertThat(jobRepository.findById(lease.jobId()).orElseThrow().status())
                .isEqualTo(AdminJobStatus.RUNNING);
        assertThat(countAudits("SUCCEEDED")).isZero();
    }

    @Test
    void staleIngestionExpirationRollsBackWhenItsTerminalAuditFails() {
        long sourceId = insertSource("audit-ingestion-expiration-source");
        var lease = runRepository.startLease(
                sourceId, "manual", "MANUAL", "audit-ingestion-expiration-worker");
        jdbcClient.sql("""
                        UPDATE ingest_run
                        SET heartbeat_at = now() - interval '2 hours'
                        WHERE id = :runId
                        """)
                .param("runId", lease.runId())
                .update();
        DataIntegrityViolationException auditFailure =
                new DataIntegrityViolationException("audit unavailable");
        doThrow(auditFailure).when(successWriter).writeInCurrentTransaction(any());

        assertThatThrownBy(() -> runExpirationService.expireStale(Duration.ofHours(1)))
                .isSameAs(auditFailure);

        assertThat(runRepository.findById(lease.runId()).orElseThrow().status())
                .isEqualTo(IngestRunStatus.RUNNING);
        assertThat(countAudits("SUCCEEDED")).isZero();
    }

    private AdminAuditEvent event(long actorId, long sourceId, Map<String, Object> summary) {
        return AdminAuditEvent.requested(
                actorId, "SOURCE_UPDATED", "SOURCE", sourceId, summary, "127.0.0.1");
    }

    private long insertUser(String username) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, role)
                        VALUES (:username, 'hash', 'ADMIN')
                        RETURNING id
                        """)
                .param("username", username)
                .query(Long.class)
                .single();
    }

    private long insertSource(String name) {
        return jdbcClient.sql("""
                        INSERT INTO source (name, base_url, type)
                        VALUES (:name, 'https://audit.example.edu', 'OFFICIAL')
                        RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private void insertAuditAt(long actorId, long targetId, OffsetDateTime createdAt) {
        repository.insert(event(actorId, targetId, Map.of("targetId", targetId)));
        jdbcClient.sql("""
                        UPDATE admin_audit_log
                        SET created_at = :createdAt
                        WHERE actor_id = :actorId
                          AND target_id = :targetId
                        """)
                .param("createdAt", createdAt)
                .param("actorId", actorId)
                .param("targetId", targetId)
                .update();
    }

    private long countAudits(String result) {
        return jdbcClient.sql("SELECT count(*) FROM admin_audit_log WHERE result = :result")
                .param("result", result)
                .query(Long.class)
                .single();
    }
}

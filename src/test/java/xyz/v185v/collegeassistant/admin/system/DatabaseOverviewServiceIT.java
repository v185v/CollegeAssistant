package xyz.v185v.collegeassistant.admin.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewDto.RecentIngestionFailure;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

@TestPropertySource(properties = {
        "app.storage.local-root=target/database-overview-service-it-storage",
        "ai.embedding.provider=openai",
        "ai.embedding.model=text-embedding-3-small",
        "ai.embedding.base-url=https://models.internal.example/v1",
        "ai.embedding.api-key=overview-secret-api-key",
        "ai.embedding.dimension=1536"
})
class DatabaseOverviewServiceIT extends DatabaseTestSupport {

    private static final long EMBEDDING_CONFIG_ID = 2L;

    @Autowired
    private DatabaseOverviewService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private EmbeddingChunkRepository embeddingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Test
    void returnsOnlyAllowlistedHealthDataFromPredefinedQueries() throws Exception {
        Long adminId = insertUser("overview-admin", "ADMIN");
        Long sourceId = insertSource("Overview source");
        Long documentId = insertDocument(sourceId, "overview-document");
        embeddingRepository.replaceForDocument(
                documentId, EMBEDDING_CONFIG_ID, List.of(chunk(documentId)));
        insertUgcPost(adminId);
        Long failedRunId = insertFailedRun(sourceId);
        insertAdminJob(adminId);
        insertAuditLog(adminId);

        DatabaseOverviewDto overview = service.getOverview(
                new AuthPrincipal(adminId, "overview-admin", UserRole.ADMIN.name()));

        assertThat(overview.databaseVersion()).isNotBlank();
        assertThat(overview.pgvectorVersion()).isNotBlank();
        assertThat(overview.flywayVersion()).isNotBlank();
        assertThat(overview.databaseSizeBytes()).isPositive();
        assertThat(overview.indexSizeBytes()).isNotNegative();
        assertThat(overview.tableCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "users", 1L,
                "source", 1L,
                "document", 1L,
                "embedding_chunk", 1L,
                "ingest_run", 1L,
                "ugc_post", 1L,
                "admin_job", 1L,
                "admin_audit_log", 1L));
        assertThat(overview.activeConnections()).isPositive();
        assertThat(overview.storageAvailable()).isTrue();
        assertThat(overview.modelStatus()).satisfies(status -> {
            assertThat(status.provider()).isEqualTo("openai");
            assertThat(status.model()).isEqualTo("text-embedding-3-small");
            assertThat(status.configured()).isTrue();
            assertThat(status.dimension()).isEqualTo(1536);
        });
        assertThat(overview.recentIngestionFailures()).containsExactly(
                new RecentIngestionFailure(
                        failedRunId,
                        sourceId,
                        "FAILED",
                        "REMOTE_TIMEOUT",
                        OffsetDateTime.parse("2026-07-25T01:00:00Z")));

        String serialized = objectMapper.writeValueAsString(overview);
        assertThat(serialized)
                .doesNotContain("overview-secret-api-key")
                .doesNotContain("https://models.internal.example/v1")
                .doesNotContain("sensitive upstream stack trace");
    }

    @Test
    void overviewRequiresAdministratorRoleAtTheServiceBoundary() {
        AuthPrincipal student = new AuthPrincipal(7L, "student", UserRole.STUDENT.name());

        assertThatThrownBy(() -> service.getOverview(student))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ADMIN_REQUIRED));
    }

    @Test
    void activeConnectionCountExcludesIdleDatabaseSessions() throws SQLException {
        Long adminId = insertUser("connection-admin", "ADMIN");
        AuthPrincipal admin = new AuthPrincipal(
                adminId, "connection-admin", UserRole.ADMIN.name());

        try (Connection idleConnection = dataSource.getConnection();
             var statement = idleConnection.createStatement()) {
            statement.execute("SELECT 1");

            DatabaseOverviewDto overview = service.getOverview(admin);
            int expectedActive = jdbcClient.sql("""
                            SELECT count(*)
                            FROM pg_stat_activity
                            WHERE datname = current_database() AND state = 'active'
                            """)
                    .query(Integer.class)
                    .single();
            int allDatabaseSessions = jdbcClient.sql("""
                            SELECT count(*)
                            FROM pg_stat_activity
                            WHERE datname = current_database()
                            """)
                    .query(Integer.class)
                    .single();

            assertThat(allDatabaseSessions).isGreaterThan(expectedActive);
            assertThat(overview.activeConnections()).isEqualTo(expectedActive);
        }
    }

    private Long insertUser(String username, String role) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, 'hash', 'Overview administrator', :role)
                        RETURNING id
                        """)
                .param("username", username)
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private Long insertSource(String name) {
        return jdbcClient.sql("""
                        INSERT INTO source (name, base_url, type, enabled)
                        VALUES (:name, 'https://overview.example.edu', 'OFFICIAL', true)
                        RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private Long insertDocument(Long sourceId, String slug) {
        return jdbcClient.sql("""
                        INSERT INTO document (
                            source_id, title, source_url, content_hash, file_object_key
                        )
                        VALUES (
                            :sourceId, 'Overview document', :sourceUrl, :contentHash, NULL
                        )
                        RETURNING id
                        """)
                .param("sourceId", sourceId)
                .param("sourceUrl", "https://overview.example.edu/" + slug)
                .param("contentHash", "hash-" + slug)
                .query(Long.class)
                .single();
    }

    private void insertUgcPost(Long authorId) {
        jdbcClient.sql("""
                        INSERT INTO ugc_post (author_id, title, content, status)
                        VALUES (:authorId, 'Overview post', 'Safe overview post', 'APPROVED')
                        """)
                .param("authorId", authorId)
                .update();
    }

    private Long insertFailedRun(Long sourceId) {
        return jdbcClient.sql("""
                        INSERT INTO ingest_run (
                            source_id, started_at, heartbeat_at, finished_at, status,
                            error_code, error_detail
                        )
                        VALUES (
                            :sourceId, '2026-07-25T00:00:00Z', '2026-07-25T00:30:00Z',
                            '2026-07-25T01:00:00Z', 'FAILED', 'REMOTE_TIMEOUT',
                            'sensitive upstream stack trace'
                        )
                        RETURNING id
                        """)
                .param("sourceId", sourceId)
                .query(Long.class)
                .single();
    }

    private void insertAdminJob(Long adminId) {
        jdbcClient.sql("""
                        INSERT INTO admin_job (
                            job_type, status, target_type, target_id, requested_by,
                            finished_at
                        )
                        VALUES (
                            'DOCUMENT_REINDEX', 'SUCCEEDED', 'DOCUMENT', 91, :adminId, now()
                        )
                        """)
                .param("adminId", adminId)
                .update();
    }

    private void insertAuditLog(Long adminId) {
        jdbcClient.sql("""
                        INSERT INTO admin_audit_log (
                            actor_id, action, target_type, target_id, result
                        )
                        VALUES (:adminId, 'OVERVIEW_TEST', 'DATABASE', NULL, 'SUCCEEDED')
                        """)
                .param("adminId", adminId)
                .update();
    }

    private EmbeddingChunkRecord chunk(Long documentId) {
        List<Double> vector = new ArrayList<>(Collections.nCopies(1536, 0.0));
        vector.set(0, 1.0);
        return new EmbeddingChunkRecord(
                null,
                EMBEDDING_CONFIG_ID,
                "DOCUMENT",
                documentId,
                0,
                "Overview vector",
                vector,
                "OFFICIAL",
                "https://overview.example.edu/document",
                "Overview document",
                "Example University",
                null,
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                Map.of(),
                null);
    }
}

package xyz.v185v.collegeassistant.admin.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import xyz.v185v.collegeassistant.admin.document.ImportStagingStore;
import xyz.v185v.collegeassistant.admin.document.StagedImportObject;
import xyz.v185v.collegeassistant.admin.job.AdminJobDispatcher;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobLease;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.storage.StoredObjectMetadata;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class ConsistencyScanServiceIT extends DatabaseTestSupport {

    private static final long EMBEDDING_CONFIG_ID = 2L;

    @Autowired
    private ConsistencyScanService scanner;

    @Autowired
    private AdminJobRepository jobRepository;

    @Autowired
    private AdminJobDispatcher dispatcher;

    @Autowired
    private EmbeddingChunkRepository embeddingRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private ObjectStorage objectStorage;

    @MockitoBean
    private ImportStagingStore stagingStore;

    @Test
    void scanRecordsTypedFindingsWithoutMutatingInconsistentState() {
        Long adminId = insertUser("scan-admin");
        AuthPrincipal admin = new AuthPrincipal(adminId, "scan-admin", UserRole.ADMIN.name());
        Long sourceId = insertSource("Consistency source");
        Long documentId = insertDocument(
                sourceId,
                "documents-run-77-item-88-attempt-0-lease-token/missing-object.pdf");
        embeddingRepository.replaceForDocument(
                999_999L, EMBEDDING_CONFIG_ID, List.of(chunk(999_999L)));
        insertStaleRun(sourceId);
        StagedImportObject staged = new StagedImportObject(
                987_654L,
                876_543L,
                12L,
                Instant.parse("2026-07-25T00:00:00Z"),
                "import-staging/987654/876543");
        when(objectStorage.list("documents")).thenReturn(List.of());
        when(stagingStore.findOlderThan(any(Instant.class))).thenReturn(List.of(staged));
        DomainSnapshot before = snapshot();

        AdminJobDto created = scanner.start(admin);
        AdminJobLease lease = jobRepository.claimNext("consistency-it").orElseThrow();
        dispatcher.execute(lease);
        AdminJobDto result = scanner.get(admin, created.id());

        assertThat(result.status()).isEqualTo(AdminJobStatus.SUCCEEDED);
        assertThat(result.items())
                .extracting(AdminJobItemDto::itemType)
                .containsExactlyInAnyOrder(
                        "MISSING_OBJECT",
                        "MISSING_ACTIVE_VECTOR",
                        "ORPHAN_VECTOR",
                        "STALE_RUN",
                        "ORPHAN_STAGING_OBJECT");
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo(AdminJobStatus.SUCCEEDED);
            assertThat(item.checkpoint()).containsExactly(Map.entry("detected", true));
            assertThat(item.itemKey()).doesNotContain("missing-object.pdf");
        });
        assertThat(result.items())
                .filteredOn(item -> item.itemType().equals("MISSING_OBJECT"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.targetType()).isEqualTo("DOCUMENT");
                    assertThat(item.targetId()).isEqualTo(documentId);
                });
        assertThat(snapshot()).isEqualTo(before);
        assertThat(countJobs("INDEX_CLEANUP")).isZero();
        assertThat(auditActions()).contains("CONSISTENCY_SCAN_REQUESTED", "ADMIN_JOB_FINISHED");
        verify(objectStorage, never()).delete(anyString());
        verify(stagingStore, never()).delete(anyLong(), anyLong());
    }

    @Test
    void scanHashesOrphanObjectIdentityAndNeverDeletesTheObject() {
        Long adminId = insertUser("orphan-admin");
        AuthPrincipal admin = new AuthPrincipal(adminId, "orphan-admin", UserRole.ADMIN.name());
        String opaqueObjectKey = "documents-job-41-item-9-attempt-0-lease-token/"
                + "5de27652-0ac1-4d63-a922-private-name.pdf";
        when(objectStorage.list("documents")).thenReturn(List.of(
                new StoredObjectMetadata(
                        opaqueObjectKey,
                        7L,
                        Instant.parse("2026-07-25T00:00:00Z"))));
        when(stagingStore.findOlderThan(any(Instant.class))).thenReturn(List.of());

        AdminJobDto created = scanner.start(admin);
        dispatcher.execute(jobRepository.claimNext("orphan-object-it").orElseThrow());
        AdminJobDto result = scanner.get(admin, created.id());

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.itemType()).isEqualTo("ORPHAN_OBJECT");
            assertThat(item.targetType()).isEqualTo("STORAGE_OBJECT");
            assertThat(item.targetId()).isNull();
            assertThat(item.itemKey()).doesNotContain(opaqueObjectKey).doesNotContain("private-name");
            assertThat(item.checkpoint()).containsExactly(Map.entry("detected", true));
        });
        verify(objectStorage, never()).delete(anyString());
    }

    private Long insertUser(String username) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, 'hash', 'Consistency administrator', 'ADMIN')
                        RETURNING id
                        """)
                .param("username", username)
                .query(Long.class)
                .single();
    }

    private Long insertSource(String name) {
        return jdbcClient.sql("""
                        INSERT INTO source (name, base_url, type, enabled)
                        VALUES (:name, 'https://consistency.example.edu', 'OFFICIAL', true)
                        RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private Long insertDocument(Long sourceId, String objectKey) {
        return jdbcClient.sql("""
                        INSERT INTO document (
                            source_id, title, source_url, content_hash,
                            file_object_key, document_status
                        )
                        VALUES (
                            :sourceId, 'Missing document',
                            'https://consistency.example.edu/missing',
                            'missing-document-hash', :objectKey, 'ACTIVE'
                        )
                        RETURNING id
                        """)
                .param("sourceId", sourceId)
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private void insertStaleRun(Long sourceId) {
        jdbcClient.sql("""
                        INSERT INTO ingest_run (
                            source_id, started_at, heartbeat_at, status, trigger_type
                        )
                        VALUES (
                            :sourceId, now() - interval '3 hours',
                            now() - interval '2 hours', 'RUNNING', 'MANUAL'
                        )
                        """)
                .param("sourceId", sourceId)
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
                "Orphan vector",
                vector,
                "OFFICIAL",
                "https://consistency.example.edu/orphan",
                "Orphan vector",
                "Example University",
                null,
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                Map.of(),
                null);
    }

    private DomainSnapshot snapshot() {
        return new DomainSnapshot(
                countRows("document"),
                countRows("embedding_chunk"),
                countRows("ingest_run"),
                jdbcClient.sql("SELECT count(*) FROM ingest_run WHERE status = 'RUNNING'")
                        .query(Long.class)
                        .single());
    }

    private long countRows(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private long countJobs(String jobType) {
        return jdbcClient.sql("SELECT count(*) FROM admin_job WHERE job_type = :jobType")
                .param("jobType", jobType)
                .query(Long.class)
                .single();
    }

    private List<String> auditActions() {
        return jdbcClient.sql("SELECT action FROM admin_audit_log ORDER BY id")
                .query(String.class)
                .list();
    }

    private record DomainSnapshot(
            long documents,
            long vectors,
            long ingestRuns,
            long runningIngestRuns
    ) {
    }
}

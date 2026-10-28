package xyz.v185v.collegeassistant.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AdminContentMigrationContractTest {

    private static final String MIGRATION_PATH =
            "db/migration/V12__admin_content_management.sql";

    @Test
    void v12DefinesConstrainedContentAndJobManagementSchema() throws Exception {
        ClassPathResource migration = new ClassPathResource(MIGRATION_PATH);
        assertThat(migration.exists())
                .as("Flyway migration %s must exist", MIGRATION_PATH)
                .isTrue();
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);
        String normalized = sql.replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);

        assertThat(normalized)
                .contains("ALTER TABLE SOURCE ALTER COLUMN BASE_URL DROP NOT NULL")
                .contains("CONNECTOR_TYPE")
                .contains("DELETION_STATUS")
                .contains("SCHEDULE_TYPE")
                .contains("SCHEDULE_ZONE")
                .contains("CRON_EXPRESSION")
                .contains("SAME_HOST_ONLY")
                .contains("INCLUDE_PATTERNS")
                .contains("EXCLUDE_PATTERNS")
                .contains("CREATED_BY BIGINT REFERENCES USERS(ID)")
                .contains("'LOCAL_UPLOAD', 'SINGLE_URL', 'SITE', 'RSS', 'SITEMAP'")
                .contains("'NONE', 'DELETING', 'DELETE_FAILED'")
                .contains("'MANUAL', 'DAILY', 'WEEKLY', 'CRON'")
                .contains("BASE_URL IS NOT NULL")
                .contains("CRON_EXPRESSION IS NOT NULL")
                .contains("REFERENCE_TYPE")
                .contains("EXTERNAL_SOURCE_URL")
                .contains("INTERNAL_LOCATOR UUID")
                .contains("ORIGINAL_FILENAME")
                .contains("NORMALIZED_FILENAME")
                .contains("DOCUMENT_STATUS")
                .contains("LAST_SEEN_AT")
                .contains("LAST_CHECKED_AT")
                .contains("DEGREE")
                .contains("COLLEGE")
                .contains("CAMPUS")
                .contains("GRADE_RANGE")
                .contains("'EXTERNAL', 'INTERNAL'")
                .contains("EXTERNAL_SOURCE_URL IS NOT NULL")
                .contains("'PROCESSING', 'ACTIVE', 'FAILED', 'DELETING', 'DELETE_FAILED'")
                .contains("TRIGGER_TYPE")
                .contains("SCHEDULED_AT")
                .contains("LEASE_TOKEN UUID")
                .contains("WORKER_ID")
                .contains("ATTEMPT")
                .contains("HEARTBEAT_AT")
                .contains("'MANUAL', 'SCHEDULED', 'IMPORT', 'RETRY'")
                .contains("'RUNNING', 'DEFERRED', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'")
                .contains("CREATE TABLE INGEST_RUN_ITEM")
                .contains("UNIQUE (RUN_ID, ITEM_KEY)")
                .contains("'URL', 'FILE'")
                .contains("'QUEUED', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED'")
                .contains("CHECKPOINT JSONB")
                .contains("CHECKPOINT_ATTEMPT")
                .contains("CREATE TABLE ADMIN_JOB")
                .contains("CREATE TABLE ADMIN_JOB_ITEM")
                .contains("'SOURCE_DELETE', 'DOCUMENT_DELETE', 'DOCUMENT_REINDEX',"
                        + " 'CONSISTENCY_SCAN', 'DATABASE_BACKUP', 'EMBEDDING_REBUILD',"
                        + " 'INDEX_CLEANUP'")
                .contains("'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL_SUCCESS',"
                        + " 'FAILED', 'CANCELLED'")
                .contains("UX_INGEST_RUN_SOURCE_ACTIVE")
                .contains("WHERE STATUS IN ('RUNNING', 'DEFERRED')")
                .contains("UX_ADMIN_JOB_ACTIVE_TARGET")
                .contains("UX_ADMIN_JOB_ACTIVE_SINGLETON")
                .contains("UX_DOCUMENT_INTERNAL_LOCATOR")
                .contains("UX_DOCUMENT_INTERNAL_CONTENT_ACTIVE")
                .contains("UX_DOCUMENT_INTERNAL_FILENAME_ACTIVE")
                .contains("INTERNAL://DOCUMENT/")
                .contains("SOURCE_URL = EXTERNAL_SOURCE_URL")
                .contains("SOURCE_URL = 'INTERNAL://DOCUMENT/' || INTERNAL_LOCATOR::TEXT")
                .contains("CREATE FUNCTION DEFAULT_EXTERNAL_DOCUMENT_REFERENCE")
                .contains("BEFORE INSERT OR UPDATE OF REFERENCE_TYPE, SOURCE_URL,"
                        + " EXTERNAL_SOURCE_URL ON DOCUMENT");
    }

    @Test
    void v12RetainsLegacyDocumentIdentityColumnsAndReplacesTheOldRunIndex()
            throws Exception {
        ClassPathResource migration = new ClassPathResource(MIGRATION_PATH);
        assertThat(migration.exists()).isTrue();
        String normalized = migration.getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);

        assertThat(normalized)
                .doesNotContain("DROP COLUMN SOURCE_URL")
                .doesNotContain("DROP COLUMN CONTENT_HASH")
                .contains("DROP INDEX UX_INGEST_RUN_SOURCE_RUNNING")
                .contains("CREATE UNIQUE INDEX UX_INGEST_RUN_SOURCE_ACTIVE");
    }
}

package xyz.v185v.collegeassistant.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AdminGovernanceMigrationContractTest {

    private static final String GOVERNANCE_MIGRATION_PATH =
            "db/migration/V13__admin_governance_operations.sql";
    private static final String CONTENT_MIGRATION_PATH =
            "db/migration/V12__admin_content_management.sql";

    @Test
    void v13DefinesRedactionSafeGovernanceAndBackupSchema() throws Exception {
        ClassPathResource migration = new ClassPathResource(GOVERNANCE_MIGRATION_PATH);
        assertThat(migration.exists())
                .as("Flyway migration %s must exist", GOVERNANCE_MIGRATION_PATH)
                .isTrue();

        String normalized = normalize(migration.getContentAsString(StandardCharsets.UTF_8));

        assertThat(normalized)
                .contains("CREATE TABLE ADMIN_AUDIT_LOG")
                .contains("ACTOR_ID BIGINT REFERENCES USERS(ID)")
                .contains("ACTION VARCHAR(64) NOT NULL")
                .contains("TARGET_TYPE VARCHAR(32) NOT NULL")
                .contains("TARGET_ID BIGINT")
                .contains("SUMMARY JSONB NOT NULL DEFAULT '{}'::JSONB")
                .contains("REMOTE_ADDRESS INET")
                .contains("RESULT VARCHAR(16) NOT NULL")
                .contains("'SUCCEEDED', 'FAILED'")
                .contains("ERROR_CODE INTEGER")
                .contains("CREATED_AT TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                .contains("ALTER TABLE UGC_REPORT")
                .contains("REVIEWER_ID BIGINT REFERENCES USERS(ID)")
                .contains("RESOLVED_AT TIMESTAMPTZ")
                .contains("CREATE TABLE DATABASE_BACKUP")
                .contains("JOB_ID BIGINT REFERENCES ADMIN_JOB(ID)")
                .contains("STORAGE_KEY VARCHAR(512)")
                .contains("SHA256 VARCHAR(64)")
                .contains("SIZE_BYTES BIGINT")
                .contains("RETENTION_DAYS INTEGER NOT NULL DEFAULT 7")
                .contains("EXPIRES_AT TIMESTAMPTZ")
                .contains("ERROR_SUMMARY VARCHAR(1000)")
                .contains("'QUEUED', 'RUNNING', 'SUCCEEDED', 'VALIDATED', 'FAILED',")
                .contains("'DELETE_FAILED'")
                .contains("CREATE UNIQUE INDEX UX_DATABASE_BACKUP_ACTIVE_SINGLETON")
                .contains("WHERE STATUS IN ('QUEUED', 'RUNNING')")
                .doesNotContain("ABSOLUTE_PATH")
                .doesNotContain("HOST_PATH");
    }

    @Test
    void v12JobItemsRetainTypedConsistencyFindingFieldsWithoutContentOrPathColumns()
            throws Exception {
        ClassPathResource migration = new ClassPathResource(CONTENT_MIGRATION_PATH);
        assertThat(migration.exists()).isTrue();

        String normalized = normalize(migration.getContentAsString(StandardCharsets.UTF_8));

        assertThat(normalized)
                .contains("CREATE TABLE ADMIN_JOB_ITEM")
                .contains("ITEM_KEY VARCHAR(512) NOT NULL")
                .contains("ITEM_TYPE VARCHAR(64) NOT NULL")
                .contains("TARGET_TYPE VARCHAR(64)")
                .contains("TARGET_ID BIGINT")
                .contains("CHECKPOINT JSONB NOT NULL DEFAULT '{}'::JSONB");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}

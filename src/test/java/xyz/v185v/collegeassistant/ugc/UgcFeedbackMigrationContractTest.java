package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class UgcFeedbackMigrationContractTest {

    private static final String MIGRATION_PATH = "db/migration/V11__ugc_feedback.sql";

    @Test
    void v11AddsDeduplicatedFeedbackAndTriggerMaintainedCounters() throws Exception {
        ClassPathResource migration = new ClassPathResource(MIGRATION_PATH);
        assertThat(migration.exists()).as("Flyway migration %s must exist", MIGRATION_PATH).isTrue();
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);
        String normalized = sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);

        assertThat(normalized)
                .contains("CREATE TABLE UGC_LIKE")
                .contains("PRIMARY KEY (POST_ID, USER_ID)")
                .contains("CREATE TABLE UGC_REPORT")
                .contains("UNIQUE (POST_ID, REPORTER_ID)")
                .contains("CHECK (CHAR_LENGTH(BTRIM(REASON)) BETWEEN 1 AND 500)")
                .contains("CHECK (STATUS IN ('OPEN', 'RESOLVED', 'DISMISSED'))")
                .contains("AFTER INSERT OR DELETE ON UGC_LIKE")
                .contains("AFTER INSERT OR DELETE ON UGC_REPORT")
                .contains("LIKES = LIKES + 1")
                .contains("LIKES = GREATEST(LIKES - 1, 0)")
                .contains("REPORTS = REPORTS + 1")
                .contains("REPORTS = GREATEST(REPORTS - 1, 0)")
                .contains("UPDATE UGC_POST SET LIKES = 0, REPORTS = 0");

        assertThat(Pattern.compile("REFERENCES\\s+UGC_POST\\s*\\(ID\\)\\s+ON\\s+DELETE\\s+CASCADE",
                        Pattern.CASE_INSENSITIVE).matcher(sql).results())
                .hasSize(2);
        assertThat(Pattern.compile("REFERENCES\\s+USERS\\s*\\(ID\\)\\s+ON\\s+DELETE\\s+CASCADE",
                        Pattern.CASE_INSENSITIVE).matcher(sql).results())
                .hasSize(2);
    }
}

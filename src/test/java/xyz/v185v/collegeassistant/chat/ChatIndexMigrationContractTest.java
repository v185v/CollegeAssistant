package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ChatIndexMigrationContractTest {

    private static final String MIGRATION_PATH =
            "db/migration/V9__chat_query_indexes.sql";
    private static final Pattern CREATE_INDEX =
            Pattern.compile("\\bCREATE\\s+INDEX\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_INDEX =
            Pattern.compile("\\bDROP\\s+INDEX\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void v9AddsChatQueryIndexesWithoutDroppingExistingIndexes() throws IOException {
        String sql = migrationSql();
        String normalized = sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);

        assertThat(CREATE_INDEX.matcher(sql).results()).hasSize(3);
        assertThat(normalized)
                .contains("CREATE INDEX IF NOT EXISTS IDX_CHAT_SESSION_USER_UPDATED_AT_ID "
                        + "ON CHAT_SESSION(USER_ID, UPDATED_AT DESC, ID DESC)")
                .contains("CREATE INDEX IF NOT EXISTS IDX_CHAT_MESSAGE_SESSION_CREATED_AT_ID "
                        + "ON CHAT_MESSAGE(SESSION_ID, CREATED_AT, ID)")
                .contains("CREATE INDEX IF NOT EXISTS IDX_CHAT_MESSAGE_STALE_ASSISTANT_PLACEHOLDER "
                        + "ON CHAT_MESSAGE(CREATED_AT, ID) "
                        + "WHERE ROLE = 'ASSISTANT' AND CONTENT = ''");
        assertThat(DROP_INDEX.matcher(sql).find()).isFalse();
    }

    private String migrationSql() throws IOException {
        ClassPathResource migration = new ClassPathResource(MIGRATION_PATH);
        assertThat(migration.exists())
                .as("Flyway migration %s must exist", MIGRATION_PATH)
                .isTrue();
        return migration.getContentAsString(StandardCharsets.UTF_8);
    }
}

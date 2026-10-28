package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class ChatIndexCatalogIT extends DatabaseTestSupport {

    private static final String SESSION_INDEX =
            "idx_chat_session_user_updated_at_id";
    private static final String MESSAGE_INDEX =
            "idx_chat_message_session_created_at_id";
    private static final String STALE_PLACEHOLDER_INDEX =
            "idx_chat_message_stale_assistant_placeholder";

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void v9ChatIndexesHaveExpectedCatalogDefinitions() {
        List<IndexDefinition> indexes = jdbcClient.sql("""
                        SELECT pgi.indexname,
                               pg_get_indexdef(idx.oid) AS index_definition
                        FROM pg_indexes pgi
                        JOIN pg_namespace ns
                          ON ns.nspname = pgi.schemaname
                        JOIN pg_class idx
                          ON idx.relnamespace = ns.oid
                         AND idx.relname = pgi.indexname
                         AND idx.relkind = 'i'
                        WHERE pgi.schemaname = current_schema()
                          AND pgi.tablename IN ('chat_session', 'chat_message')
                          AND pgi.indexname IN (
                              'idx_chat_session_user_updated_at_id',
                              'idx_chat_message_session_created_at_id',
                              'idx_chat_message_stale_assistant_placeholder'
                          )
                        """)
                .query((resultSet, rowNumber) -> new IndexDefinition(
                        resultSet.getString("indexname"),
                        resultSet.getString("index_definition")))
                .list();
        Map<String, String> definitions = indexes.stream()
                .collect(Collectors.toMap(IndexDefinition::name, IndexDefinition::sql));

        assertThat(definitions).containsOnlyKeys(
                SESSION_INDEX, MESSAGE_INDEX, STALE_PLACEHOLDER_INDEX);
        assertThat(normalized(definitions, SESSION_INDEX))
                .contains("USING BTREE (USER_ID, UPDATED_AT DESC, ID DESC)");
        assertThat(normalized(definitions, MESSAGE_INDEX))
                .contains("USING BTREE (SESSION_ID, CREATED_AT, ID)");
        assertThat(normalized(definitions, STALE_PLACEHOLDER_INDEX))
                .contains("USING BTREE (CREATED_AT, ID)")
                .contains(" WHERE ")
                .containsPattern("WHERE .*ROLE.*= 'ASSISTANT'.* AND .*CONTENT.*= ''.*")
                .doesNotContain(" OR ");
    }

    private String normalized(Map<String, String> definitions, String indexName) {
        return definitions.get(indexName)
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    private record IndexDefinition(String name, String sql) {
    }
}

package xyz.v185v.collegeassistant.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class EmbeddingVersionMigrationIT {

    private static final String URL = requiredEnvironment("TEST_DB_URL");
    private static final String USERNAME = requiredEnvironment("TEST_DB_USERNAME");
    private static final String PASSWORD = requiredEnvironment("TEST_DB_PASSWORD");

    @BeforeAll
    static void installVectorInPublicSchema() throws SQLException {
        try (Connection connection = openDatabaseConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        }
    }

    @Test
    void populatedV13UpgradePreservesEveryLegacyVectorAndRoutesOmittedIdsToBootstrap()
            throws Exception {
        withDisposableSchema("populated", schema -> {
            migrate(schema, MigrationVersion.fromVersion("13"));

            List<String> legacyRows;
            long legacyMaximumId;
            try (Connection connection = openSchemaConnection(schema)) {
                insertLegacyFixture(
                        connection, "DOCUMENT", 101L, 0, "First chunk", 0.1f,
                        "OFFICIAL", "https://example.edu/a", "A title", "Example University",
                        "2025-09-01T01:02:03Z", "UNDERGRADUATE", "ENGINEERING", "MAIN",
                        "2025-2029", "{\"tags\":[\"a\",\"b\"],\"rank\":1}",
                        "2026-07-20T01:02:03Z");
                insertLegacyFixture(
                        connection, "DOCUMENT", 101L, 1, "Second chunk", -0.25f,
                        "OFFICIAL", null, null, null, null, null, null, null, null,
                        "{\"nested\":{\"enabled\":true}}", "2026-07-20T01:02:04Z");
                insertLegacyFixture(
                        connection, "UGC", 202L, 0, "UGC chunk", 0.75f,
                        "UGC", "https://community.example/post/202", "Community title", null,
                        "2026-01-02T03:04:05Z", "GENERAL", "GENERAL", "GENERAL", "GENERAL",
                        "{\"likes\":7,\"reports\":0}", "2026-07-20T01:02:05Z");
                legacyRows = vectorRows(connection);
                legacyMaximumId = queryLong(connection, "SELECT max(id) FROM embedding_chunk");
            }

            migrate(schema, MigrationVersion.fromVersion("14"));

            try (Connection connection = openSchemaConnection(schema)) {
                assertThat(latestFlywayVersion(connection)).isEqualTo("14");
                assertThat(vectorRows(connection)).containsExactlyElementsOf(legacyRows);
                assertThat(queryLong(connection, "SELECT count(*) FROM embedding_chunk"))
                        .isEqualTo(3L);

                long bootstrapId = bootstrapEmbeddingId(connection);
                assertThat(queryLong(connection, """
                        SELECT count(*)
                        FROM embedding_chunk
                        WHERE embedding_config_id = %d
                        """.formatted(bootstrapId))).isEqualTo(3L);
                assertThat(queryStrings(connection, """
                        SELECT DISTINCT tableoid::regclass::text
                        FROM embedding_chunk
                        """)).containsExactly("embedding_chunk_cfg_" + bootstrapId);
                assertThat(queryString(connection, """
                        SELECT relkind::text
                        FROM pg_class
                        JOIN pg_namespace ON pg_namespace.oid = pg_class.relnamespace
                        WHERE pg_namespace.nspname = current_schema()
                          AND pg_class.relname = 'embedding_chunk'
                        """)).isEqualTo("p");
                assertThat(hnswIndexCount(connection, "embedding_chunk_cfg_" + bootstrapId))
                        .isGreaterThanOrEqualTo(1L);

                long nextSequenceValue = queryLong(
                        connection, "SELECT nextval('embedding_chunk_id_seq')");
                assertThat(nextSequenceValue).isGreaterThan(legacyMaximumId);

                long legacyWriteId = insertMinimalChunk(
                        connection, null, "DOCUMENT", 303L, 0, "Legacy insert", 0.5f);
                assertThat(legacyWriteId).isGreaterThan(nextSequenceValue);
                assertThat(queryString(connection, """
                        SELECT configuration.configuration_key
                        FROM embedding_chunk chunk
                        JOIN model_configuration configuration
                          ON configuration.id = chunk.embedding_config_id
                        WHERE chunk.id = %d
                        """.formatted(legacyWriteId))).isEqualTo("bootstrap-embedding");
            }
        });
    }

    @Test
    void cleanV14EnforcesModelSingletonsAndVersionScopedVectorIdentity() throws Exception {
        withDisposableSchema("constraints", schema -> {
            migrate(schema, null);

            try (Connection connection = openSchemaConnection(schema)) {
                assertThat(queryStrings(connection, """
                        SELECT configuration_key || '|' || model_kind || '|' || status || '|'
                               || COALESCE(dimension::text, '')
                        FROM model_configuration
                        ORDER BY id
                        """)).containsExactly(
                                "bootstrap-chat|CHAT|ACTIVE|",
                                "bootstrap-embedding|EMBEDDING|ACTIVE|1536");
                assertThat(queryStrings(connection, """
                        SELECT model_kind || ':' || count(*)::text
                        FROM model_configuration
                        WHERE status = 'ACTIVE'
                        GROUP BY model_kind
                        ORDER BY model_kind
                        """)).containsExactly("CHAT:1", "EMBEDDING:1");

                assertSqlState(() -> insertConfiguration(
                        connection, "invalid-kind", "OTHER", "PENDING", null), "23514");
                assertSqlState(() -> insertConfiguration(
                        connection, "invalid-status", "CHAT", "UNKNOWN", null), "23514");
                assertSqlState(() -> insertConfiguration(
                        connection, "second-active-chat", "CHAT", "ACTIVE", null), "23505");

                long pendingId = insertConfiguration(
                        connection, "candidate-embedding", "EMBEDDING", "PENDING", 1536);
                assertSqlState(() -> insertConfiguration(
                        connection, "second-pending-embedding", "EMBEDDING", "PENDING", 1536),
                        "23505");

                long bootstrapId = bootstrapEmbeddingId(connection);
                insertMinimalChunk(
                        connection, bootstrapId, "DOCUMENT", 501L, 0, "Active vector", 0.2f);
                createEmbeddingPartition(connection, pendingId);
                insertMinimalChunk(
                        connection, pendingId, "DOCUMENT", 501L, 0, "Candidate vector", 0.3f);
                assertSqlState(() -> insertMinimalChunk(
                        connection, pendingId, "DOCUMENT", 501L, 0,
                        "Duplicate candidate", 0.4f), "23505");

                assertThat(hnswIndexCount(connection, "embedding_chunk_cfg_" + pendingId))
                        .isGreaterThanOrEqualTo(1L);
                assertThat(constraintDefinitions(connection, "embedding_chunk"))
                        .contains("PRIMARY KEY (embedding_config_id, id)")
                        .contains("UNIQUE (embedding_config_id, doc_ref_type, doc_ref_id, chunk_index)")
                        .contains("FOREIGN KEY (embedding_config_id) REFERENCES model_configuration(id)")
                        .doesNotContain("UNIQUE (doc_ref_type, doc_ref_id, chunk_index)");
                assertThat(columnDefault(connection, "embedding_chunk", "embedding_config_id"))
                        .contains("active_embedding_config_id");
                assertThat(isNullable(connection, "embedding_chunk", "embedding_config_id"))
                        .isEqualTo("NO");
            }
        });
    }

    private static long insertLegacyFixture(
            Connection connection,
            String docRefType,
            long docRefId,
            int chunkIndex,
            String chunkText,
            float vectorValue,
            String sourceType,
            String sourceUrl,
            String title,
            String sourceOrg,
            String publishedAt,
            String degree,
            String college,
            String campus,
            String gradeRange,
            String meta,
            String createdAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO embedding_chunk (
                    doc_ref_type, doc_ref_id, chunk_index, chunk_text, embedding,
                    source_type, source_url, title, source_org, published_at,
                    degree, college, campus, grade_range, meta, created_at
                )
                VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, ?, ?, CAST(? AS timestamptz),
                        ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS timestamptz))
                RETURNING id
                """)) {
            statement.setString(1, docRefType);
            statement.setLong(2, docRefId);
            statement.setInt(3, chunkIndex);
            statement.setString(4, chunkText);
            statement.setString(5, vector(vectorValue));
            statement.setString(6, sourceType);
            statement.setString(7, sourceUrl);
            statement.setString(8, title);
            statement.setString(9, sourceOrg);
            statement.setString(10, publishedAt);
            statement.setString(11, degree);
            statement.setString(12, college);
            statement.setString(13, campus);
            statement.setString(14, gradeRange);
            statement.setString(15, meta);
            statement.setString(16, createdAt);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long insertMinimalChunk(
            Connection connection,
            Long configurationId,
            String docRefType,
            long docRefId,
            int chunkIndex,
            String chunkText,
            float vectorValue
    ) throws SQLException {
        String sql = configurationId == null ? """
                INSERT INTO embedding_chunk (
                    doc_ref_type, doc_ref_id, chunk_index, chunk_text, embedding, source_type
                )
                VALUES (?, ?, ?, ?, CAST(? AS vector), 'OFFICIAL')
                RETURNING id
                """ : """
                INSERT INTO embedding_chunk (
                    embedding_config_id, doc_ref_type, doc_ref_id, chunk_index,
                    chunk_text, embedding, source_type
                )
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector), 'OFFICIAL')
                RETURNING id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (configurationId != null) {
                statement.setLong(parameter++, configurationId);
            }
            statement.setString(parameter++, docRefType);
            statement.setLong(parameter++, docRefId);
            statement.setInt(parameter++, chunkIndex);
            statement.setString(parameter++, chunkText);
            statement.setString(parameter, vector(vectorValue));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long insertConfiguration(
            Connection connection,
            String key,
            String kind,
            String status,
            Integer dimension
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO model_configuration (
                    configuration_key, model_kind, status, dimension
                )
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)) {
            statement.setString(1, key);
            statement.setString(2, kind);
            statement.setString(3, status);
            if (dimension == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setInt(4, dimension);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void createEmbeddingPartition(Connection connection, long configurationId)
            throws SQLException {
        if (configurationId <= 0) {
            throw new IllegalArgumentException("configurationId must be positive");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE embedding_chunk_cfg_%d
                    PARTITION OF embedding_chunk FOR VALUES IN (%d)
                    """.formatted(configurationId, configurationId));
        }
    }

    private static List<String> vectorRows(Connection connection) throws SQLException {
        return queryStrings(connection, """
                SELECT jsonb_build_array(
                    id, doc_ref_type, doc_ref_id, chunk_index, chunk_text,
                    embedding::text, source_type, source_url, title, source_org,
                    published_at, degree, college, campus, grade_range, meta, created_at
                )::text
                FROM embedding_chunk
                ORDER BY id
                """);
    }

    private static long bootstrapEmbeddingId(Connection connection) throws SQLException {
        return queryLong(connection, """
                SELECT id
                FROM model_configuration
                WHERE configuration_key = 'bootstrap-embedding'
                """);
    }

    private static long hnswIndexCount(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = ?
                  AND indexdef ILIKE '% USING hnsw %'
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static String constraintDefinitions(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_get_constraintdef(pg_constraint.oid)
                FROM pg_constraint
                JOIN pg_class ON pg_class.oid = pg_constraint.conrelid
                JOIN pg_namespace ON pg_namespace.oid = pg_class.relnamespace
                WHERE pg_namespace.nspname = current_schema()
                  AND pg_class.relname = ?
                ORDER BY pg_constraint.conname
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                StringBuilder definitions = new StringBuilder();
                while (resultSet.next()) {
                    definitions.append(resultSet.getString(1)).append('\n');
                }
                return definitions.toString();
            }
        }
    }

    private static String columnDefault(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static String isNullable(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static String vector(float value) {
        String element = Float.toString(value);
        return "[" + String.join(",", java.util.Collections.nCopies(1536, element)) + "]";
    }

    private static void migrate(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(URL, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static void withDisposableSchema(String label, SchemaTest test) throws Exception {
        String schema = "embedding_v14_" + label + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createSchema(schema);
        try {
            test.run(schema);
        } finally {
            dropSchema(schema);
        }
    }

    private static void createSchema(String schema) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private static void dropSchema(String schema) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static Connection openDatabaseConnection() throws SQLException {
        return DriverManager.getConnection(URL, USERNAME, PASSWORD);
    }

    private static Connection openSchemaConnection(String schema) throws SQLException {
        Connection connection = openDatabaseConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema + ", public");
        }
        return connection;
    }

    private static String latestFlywayVersion(Connection connection) throws SQLException {
        return queryString(connection, """
                SELECT version
                FROM flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """);
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static List<String> queryStrings(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
            return List.copyOf(rows);
        }
    }

    private static void assertSqlState(SqlOperation operation, String expectedSqlState) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo(expectedSqlState);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "EmbeddingVersionMigrationIT requires nonblank " + name
                            + " and a disposable PostgreSQL database");
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface SchemaTest {
        void run(String schema) throws Exception;
    }
}

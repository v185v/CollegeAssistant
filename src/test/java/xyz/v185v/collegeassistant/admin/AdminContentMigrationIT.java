package xyz.v185v.collegeassistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class AdminContentMigrationIT {

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
    void cleanMigrationCreatesTheCompleteV12Catalog() throws Exception {
        withDisposableSchema("clean", schema -> {
            migrate(schema, MigrationVersion.fromVersion("12"));

            try (Connection connection = openSchemaConnection(schema)) {
                assertThat(latestFlywayVersion(connection)).isEqualTo("12");
                assertThat(columnNames(connection, "source")).contains(
                        "connector_type", "deletion_status", "schedule_type",
                        "schedule_zone", "cron_expression", "same_host_only",
                        "max_depth", "max_pages", "max_concurrency",
                        "include_patterns", "exclude_patterns", "next_run_at",
                        "last_run_at", "created_by");
                assertThat(columnNames(connection, "document")).contains(
                        "reference_type", "external_source_url", "internal_locator",
                        "original_filename", "normalized_filename", "document_status",
                        "etag", "http_last_modified", "last_seen_at", "last_checked_at",
                        "degree", "college", "campus", "grade_range");
                assertThat(columnNames(connection, "ingest_run")).contains(
                        "trigger_type", "scheduled_at", "discovered", "attempt",
                        "worker_id", "lease_token", "claimed_at", "heartbeat_at");
                assertThat(columnNames(connection, "ingest_run_item")).contains(
                        "run_id", "item_key", "item_kind", "status", "document_id",
                        "retry_count", "next_attempt_at", "error_code", "error_summary",
                        "checkpoint", "checkpoint_attempt");
                assertThat(columnNames(connection, "admin_job")).contains(
                        "job_type", "status", "target_type", "target_id", "requested_by",
                        "attempt", "worker_id", "lease_token", "claimed_at", "heartbeat_at",
                        "error_code", "error_summary", "request_payload");
                assertThat(columnNames(connection, "admin_job_item")).contains(
                        "job_id", "item_key", "item_type", "status", "retry_count",
                        "error_code", "error_summary", "checkpoint", "checkpoint_attempt");

                String constraints = catalogConstraints(connection).toUpperCase(Locale.ROOT);
                assertThat(constraints)
                        .contains("CK_SOURCE_CONNECTOR_TYPE")
                        .contains("CK_SOURCE_CONNECTOR_LOCATION")
                        .contains("CK_SOURCE_SCHEDULE")
                        .contains("CK_DOCUMENT_REFERENCE_IDENTITY")
                        .contains("CK_DOCUMENT_STATUS")
                        .contains("CK_INGEST_RUN_TRIGGER_TYPE")
                        .contains("CK_INGEST_RUN_STATUS")
                        .contains("CK_INGEST_RUN_ITEM_STATUS")
                        .contains("CK_ADMIN_JOB_TYPE")
                        .contains("CK_ADMIN_JOB_STATUS")
                        .contains("CK_ADMIN_JOB_ITEM_STATUS");

                String indexes = catalogIndexes(connection).toUpperCase(Locale.ROOT);
                assertThat(indexes)
                        .contains("UX_INGEST_RUN_SOURCE_ACTIVE")
                        .contains("RUNNING")
                        .contains("DEFERRED")
                        .contains("UX_ADMIN_JOB_ACTIVE_TARGET")
                        .contains("UX_ADMIN_JOB_ACTIVE_SINGLETON")
                        .contains("UX_DOCUMENT_INTERNAL_LOCATOR")
                        .contains("UX_DOCUMENT_INTERNAL_CONTENT_ACTIVE")
                        .contains("UX_DOCUMENT_INTERNAL_FILENAME_ACTIVE");

                assertThat(foreignKeyColumns(connection)).contains(
                        "source.created_by->users.id",
                        "ingest_run_item.run_id->ingest_run.id",
                        "ingest_run_item.document_id->document.id",
                        "admin_job.requested_by->users.id",
                        "admin_job_item.job_id->admin_job.id");

                assertCheckViolation(connection, """
                        INSERT INTO source (name, base_url, type, connector_type)
                        VALUES ('无地址远程源', NULL, 'OFFICIAL', 'SINGLE_URL')
                        """);
                assertCheckViolation(connection, """
                        INSERT INTO source (
                            name, base_url, type, connector_type,
                            schedule_type, cron_expression
                        )
                        VALUES (
                            '无表达式计划', 'https://schedule.example.edu', 'OFFICIAL',
                            'SINGLE_URL', 'CRON', NULL
                        )
                        """);
            }
        });
    }

    @Test
    void populatedV11UpgradePreservesRowsAndBackfillsSafeDefaults() throws Exception {
        withDisposableSchema("upgrade", schema -> {
            migrate(schema, MigrationVersion.fromVersion("11"));

            long enabledSource;
            long disabledSource;
            try (Connection connection = openSchemaConnection(schema)) {
                enabledSource = insertSource(
                        connection, "教务公告", "https://example.edu/notices", true);
                disabledSource = insertSource(
                        connection, "历史资料", "https://archive.example.edu/root", false);
                insertExternalDocument(connection, enabledSource, "培养方案",
                        "https://example.edu/notices/plan", "hash-plan");
                insertExternalDocument(connection, disabledSource, "旧版校历",
                        "https://archive.example.edu/calendar", "hash-calendar");
                insertLegacyRun(connection, enabledSource, "SUCCESS", true);
                insertLegacyRun(connection, disabledSource, "RUNNING", false);
            }

            migrate(schema, MigrationVersion.fromVersion("12"));

            try (Connection connection = openSchemaConnection(schema)) {
                assertThat(latestFlywayVersion(connection)).isEqualTo("12");
                assertThat(queryLong(connection, "SELECT count(*) FROM source")).isEqualTo(2);
                assertThat(queryLong(connection, "SELECT count(*) FROM document")).isEqualTo(2);
                assertThat(queryLong(connection, "SELECT count(*) FROM ingest_run")).isEqualTo(2);

                assertThat(queryRows(connection, """
                        SELECT name, base_url, enabled::text, connector_type, deletion_status,
                               schedule_type, schedule_zone, same_host_only::text,
                               max_depth, max_pages, max_concurrency,
                               include_patterns::text, exclude_patterns::text
                        FROM source
                        ORDER BY id
                        """))
                        .containsExactly(
                                List.of("教务公告", "https://example.edu/notices", "true",
                                        "SINGLE_URL", "NONE", "MANUAL", "Asia/Shanghai",
                                        "true", "3", "500", "4", "[]", "[]"),
                                List.of("历史资料", "https://archive.example.edu/root", "false",
                                        "SINGLE_URL", "NONE", "MANUAL", "Asia/Shanghai",
                                        "true", "3", "500", "4", "[]", "[]"));

                assertThat(queryRows(connection, """
                        SELECT title, source_url, external_source_url, content_hash,
                               reference_type, document_status,
                               (internal_locator IS NULL)::text,
                               (last_seen_at IS NOT NULL)::text
                        FROM document
                        ORDER BY id
                        """))
                        .containsExactly(
                                List.of("培养方案", "https://example.edu/notices/plan",
                                        "https://example.edu/notices/plan", "hash-plan",
                                        "EXTERNAL", "ACTIVE", "true", "true"),
                                List.of("旧版校历", "https://archive.example.edu/calendar",
                                        "https://archive.example.edu/calendar", "hash-calendar",
                                        "EXTERNAL", "ACTIVE", "true", "true"));

                assertThat(queryRows(connection, """
                        SELECT status, trigger_type, discovered, attempt,
                               (worker_id IS NULL)::text,
                               (lease_token IS NULL)::text,
                               (heartbeat_at IS NOT NULL)::text
                        FROM ingest_run
                        ORDER BY id
                        """))
                        .containsExactly(
                                List.of("SUCCESS", "MANUAL", "0", "0", "true", "true", "true"),
                                List.of("RUNNING", "MANUAL", "0", "0", "true", "true", "true"));

                assertThatThrownBy(() -> insertDeferredRun(connection, disabledSource))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("23505");

                insertExternalDocument(connection, enabledSource, "兼容写入",
                        "https://example.edu/notices/compatibility", "hash-compatibility");
                assertThat(queryRows(connection, """
                        SELECT source_url, external_source_url, reference_type
                        FROM document
                        WHERE content_hash = 'hash-compatibility'
                        """))
                        .containsExactly(List.of(
                                "https://example.edu/notices/compatibility",
                                "https://example.edu/notices/compatibility",
                                "EXTERNAL"));

                long localSource = insertLocalSource(connection);
                UUID locator = UUID.randomUUID();
                insertInternalDocument(connection, localSource, locator);
                assertThat(queryRows(connection, """
                        SELECT reference_type, source_url, internal_locator::text,
                               original_filename, normalized_filename, document_status
                        FROM document
                        WHERE source_id = %d
                        """.formatted(localSource)))
                        .containsExactly(List.of(
                                "INTERNAL", "internal://document/" + locator, locator.toString(),
                                "培养方案.PDF", "培养方案.pdf", "ACTIVE"));
            }
        });
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

    private static long insertSource(
            Connection connection,
            String name,
            String baseUrl,
            boolean enabled
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO source (name, base_url, type, enabled)
                VALUES (?, ?, 'OFFICIAL', ?)
                RETURNING id
                """)) {
            statement.setString(1, name);
            statement.setString(2, baseUrl);
            statement.setBoolean(3, enabled);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void insertExternalDocument(
            Connection connection,
            long sourceId,
            String title,
            String sourceUrl,
            String contentHash
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document (source_id, title, source_url, content_hash)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, sourceId);
            statement.setString(2, title);
            statement.setString(3, sourceUrl);
            statement.setString(4, contentHash);
            statement.executeUpdate();
        }
    }

    private static void insertLegacyRun(
            Connection connection,
            long sourceId,
            String status,
            boolean terminal
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ingest_run (
                    source_id, status, finished_at, added, updated, skipped, failed, reason
                )
                VALUES (?, ?, CASE WHEN ? THEN now() ELSE NULL END, 1, 2, 3, 0, 'fixture')
                """)) {
            statement.setLong(1, sourceId);
            statement.setString(2, status);
            statement.setBoolean(3, terminal);
            statement.executeUpdate();
        }
    }

    private static void insertDeferredRun(Connection connection, long sourceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ingest_run (source_id, status, trigger_type)
                VALUES (?, 'DEFERRED', 'RETRY')
                """)) {
            statement.setLong(1, sourceId);
            statement.executeUpdate();
        }
    }

    private static long insertLocalSource(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO source (name, base_url, type, connector_type)
                VALUES ('本地资料库', NULL, 'OFFICIAL', 'LOCAL_UPLOAD')
                RETURNING id
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void insertInternalDocument(
            Connection connection,
            long sourceId,
            UUID locator
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document (
                    source_id, title, source_url, content_hash, reference_type,
                    internal_locator, original_filename, normalized_filename,
                    document_status, file_object_key
                )
                VALUES (?, '培养方案', ?, 'sha256-local', 'INTERNAL', ?, ?, ?, 'ACTIVE', 'obj-1')
                """)) {
            statement.setLong(1, sourceId);
            statement.setString(2, "internal://document/" + locator);
            statement.setObject(3, locator);
            statement.setString(4, "培养方案.PDF");
            statement.setString(5, "培养方案.pdf");
            statement.executeUpdate();
        }
    }

    private static Set<String> columnNames(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                var names = new java.util.LinkedHashSet<String>();
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
                return names;
            }
        }
    }

    private static String catalogConstraints(Connection connection) throws SQLException {
        return queryJoined(connection, """
                SELECT constraint_name || ': ' || pg_get_constraintdef(pg_constraint.oid)
                FROM information_schema.table_constraints
                JOIN pg_constraint ON pg_constraint.conname = constraint_name
                JOIN pg_class ON pg_class.oid = pg_constraint.conrelid
                JOIN pg_namespace ON pg_namespace.oid = pg_class.relnamespace
                WHERE table_schema = current_schema()
                  AND pg_namespace.nspname = current_schema()
                ORDER BY constraint_name
                """);
    }

    private static String catalogIndexes(Connection connection) throws SQLException {
        return queryJoined(connection, """
                SELECT indexname || ': ' || indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                ORDER BY indexname
                """);
    }

    private static List<String> foreignKeyColumns(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT tc.table_name || '.' || kcu.column_name || '->'
                               || ccu.table_name || '.' || ccu.column_name
                        FROM information_schema.table_constraints tc
                        JOIN information_schema.key_column_usage kcu
                          ON tc.constraint_name = kcu.constraint_name
                         AND tc.constraint_schema = kcu.constraint_schema
                        JOIN information_schema.constraint_column_usage ccu
                          ON tc.constraint_name = ccu.constraint_name
                         AND tc.constraint_schema = ccu.constraint_schema
                        WHERE tc.constraint_type = 'FOREIGN KEY'
                          AND tc.table_schema = current_schema()
                        ORDER BY 1
                        """)) {
            var values = new java.util.ArrayList<String>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private static String latestFlywayVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static void assertCheckViolation(Connection connection, String sql) {
        assertThatThrownBy(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        })
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("23514");
    }

    private static List<List<String>> queryRows(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            var rows = new java.util.ArrayList<List<String>>();
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                var row = new java.util.ArrayList<String>(columns);
                for (int index = 1; index <= columns; index++) {
                    row.add(resultSet.getString(index));
                }
                rows.add(List.copyOf(row));
            }
            return rows;
        }
    }

    private static String queryJoined(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            var values = new StringBuilder();
            while (resultSet.next()) {
                values.append(resultSet.getString(1)).append('\n');
            }
            return values.toString();
        }
    }

    private static void withDisposableSchema(String label, SchemaTest test) throws Exception {
        String schema = "admin_v12_" + label + "_"
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

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "AdminContentMigrationIT requires nonblank " + name
                            + " and a disposable PostgreSQL database");
        }
        return value;
    }

    @FunctionalInterface
    private interface SchemaTest {
        void run(String schema) throws Exception;
    }
}

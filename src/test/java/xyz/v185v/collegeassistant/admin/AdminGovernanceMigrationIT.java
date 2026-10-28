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
class AdminGovernanceMigrationIT {

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
    void cleanMigrationCreatesConstrainedGovernanceCatalog() throws Exception {
        withDisposableSchema("clean", schema -> {
            migrate(schema, MigrationVersion.fromVersion("13"));

            try (Connection connection = openSchemaConnection(schema)) {
                assertThat(latestFlywayVersion(connection)).isEqualTo("13");
                assertThat(flywayVersionSucceeded(connection, "13")).isTrue();
                assertThat(columnNames(connection, "admin_audit_log")).contains(
                        "actor_id", "action", "target_type", "target_id", "summary",
                        "remote_address", "result", "error_code", "created_at");
                assertThat(columnNames(connection, "ugc_report")).contains(
                        "reviewer_id", "resolved_at");
                assertThat(columnNames(connection, "database_backup")).contains(
                        "job_id", "status", "format", "storage_key", "sha256", "size_bytes",
                        "requested_by", "started_at", "finished_at", "retention_days",
                        "expires_at", "error_summary", "created_at", "updated_at");

                String constraints = catalogConstraints(connection).toUpperCase(Locale.ROOT);
                assertThat(constraints)
                        .contains("CK_ADMIN_AUDIT_LOG_RESULT")
                        .contains("SUCCEEDED")
                        .contains("FAILED")
                        .contains("CK_ADMIN_AUDIT_LOG_SUMMARY")
                        .contains("CK_DATABASE_BACKUP_STATUS")
                        .contains("VALIDATED")
                        .contains("DELETE_FAILED")
                        .contains("CK_DATABASE_BACKUP_RETENTION_DAYS")
                        .contains("CK_DATABASE_BACKUP_SIZE_BYTES");

                String indexes = catalogIndexes(connection).toUpperCase(Locale.ROOT);
                assertThat(indexes)
                        .contains("UX_DATABASE_BACKUP_ACTIVE_SINGLETON")
                        .contains("QUEUED")
                        .contains("RUNNING");

                assertThat(foreignKeyColumns(connection)).contains(
                        "admin_audit_log.actor_id->users.id",
                        "ugc_report.reviewer_id->users.id",
                        "database_backup.job_id->admin_job.id",
                        "database_backup.requested_by->users.id");

                assertSqlState(() -> insertBackup(connection, "INVALID"), "23514");
                insertBackup(connection, "QUEUED");
                assertSqlState(() -> insertBackup(connection, "RUNNING"), "23505");
                insertBackup(connection, "VALIDATED");
            }
        });
    }

    @Test
    void populatedV12UpgradePreservesOpenReportsAndExistingJobs() throws Exception {
        withDisposableSchema("upgrade", schema -> {
            migrate(schema, MigrationVersion.fromVersion("12"));

            long reporterId;
            long jobId;
            try (Connection connection = openSchemaConnection(schema)) {
                long authorId = insertUser(connection, "governance-author");
                reporterId = insertUser(connection, "governance-reporter");
                long postId = insertPost(connection, authorId);
                insertOpenReport(connection, postId, reporterId);
                jobId = insertConsistencyJob(connection, reporterId);
            }

            migrate(schema, MigrationVersion.fromVersion("13"));

            try (Connection connection = openSchemaConnection(schema)) {
                assertThat(latestFlywayVersion(connection)).isEqualTo("13");
                assertThat(flywayVersionSucceeded(connection, "13")).isTrue();
                assertThat(queryRows(connection, """
                        SELECT status, (reviewer_id IS NULL)::text, (resolved_at IS NULL)::text
                        FROM ugc_report
                        """))
                        .containsExactly(List.of("OPEN", "true", "true"));
                assertThat(queryLong(connection,
                        "SELECT count(*) FROM admin_job WHERE id = " + jobId)).isEqualTo(1);

                insertAuditLog(connection, reporterId);
                insertTypedConsistencyFinding(connection, jobId);
                assertThat(queryRows(connection, """
                        SELECT item_key, item_type, target_type, target_id::text, status
                        FROM admin_job_item
                        WHERE job_id = %d
                        """.formatted(jobId)))
                        .containsExactly(List.of(
                                "document:123", "MISSING_OBJECT", "DOCUMENT", "123", "QUEUED"));
                assertThat(columnNames(connection, "admin_job_item"))
                        .doesNotContain("content", "path", "absolute_path");
                assertThat(queryRows(connection, """
                        SELECT action, target_type, target_id::text, summary::text,
                               remote_address::text, result, error_code::text
                        FROM admin_audit_log
                        """))
                        .containsExactly(List.of(
                                "REPORT_REVIEW", "UGC_REPORT", "1", "{\"status\": \"OPEN\"}",
                                "127.0.0.1/32", "SUCCEEDED", "0"));
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

    private static long insertUser(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (username, password_hash, role)
                VALUES (?, 'hash', 'ADMIN')
                RETURNING id
                """)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long insertPost(Connection connection, long authorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ugc_post (author_id, title, content)
                VALUES (?, 'Existing post', 'Existing content')
                RETURNING id
                """)) {
            statement.setLong(1, authorId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void insertOpenReport(Connection connection, long postId, long reporterId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ugc_report (post_id, reporter_id, reason)
                VALUES (?, ?, 'Needs review')
                """)) {
            statement.setLong(1, postId);
            statement.setLong(2, reporterId);
            statement.executeUpdate();
        }
    }

    private static long insertConsistencyJob(Connection connection, long requestedBy)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_job (job_type, target_type, requested_by)
                VALUES ('CONSISTENCY_SCAN', 'DATABASE', ?)
                RETURNING id
                """)) {
            statement.setLong(1, requestedBy);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void insertAuditLog(Connection connection, long actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_audit_log (
                    actor_id, action, target_type, target_id, summary,
                    remote_address, result, error_code
                )
                VALUES (?, 'REPORT_REVIEW', 'UGC_REPORT', 1, '{"status":"OPEN"}'::jsonb,
                        '127.0.0.1', 'SUCCEEDED', 0)
                """)) {
            statement.setLong(1, actorId);
            statement.executeUpdate();
        }
    }

    private static void insertTypedConsistencyFinding(Connection connection, long jobId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_job_item (
                    job_id, item_key, item_type, target_type, target_id
                )
                VALUES (?, 'document:123', 'MISSING_OBJECT', 'DOCUMENT', 123)
                """)) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
    }

    private static void insertBackup(Connection connection, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO database_backup (status)
                VALUES (?)
                """)) {
            statement.setString(1, status);
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

    private static boolean flywayVersionSucceeded(Connection connection, String version)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT success
                FROM flyway_schema_history
                WHERE version = ?
                """)) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
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

    private static void assertSqlState(SqlOperation operation, String expectedSqlState) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo(expectedSqlState);
    }

    private static void withDisposableSchema(String label, SchemaTest test) throws Exception {
        String schema = "admin_v13_" + label + "_"
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
                    "AdminGovernanceMigrationIT requires nonblank " + name
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

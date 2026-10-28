package xyz.v185v.collegeassistant.auth;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class UserRepositoryIT extends DatabaseTestSupport {

    private static final String ADMIN_ROLE_LOCK_SQL = """
            SELECT pg_advisory_xact_lock(hashtextextended('admin-role-state', 0))
            """;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void hasEnabledAdminIgnoresDisabledAdministrators() {
        insertUser("disabled-admin", "old-hash", "ADMIN", false, 4);

        assertThat(userRepository.hasEnabledAdmin()).isFalse();

        insertUser("enabled-admin", "other-hash", "ADMIN", true, 2);

        assertThat(userRepository.hasEnabledAdmin()).isTrue();
    }

    @Test
    void bootstrapAdminCreatesEnabledAdministratorWhenUsernameIsNew() {
        assertThat(userRepository.bootstrapAdmin("operator", "encoded-password")).isTrue();

        UserAccount created = userRepository.findByUsername("operator").orElseThrow();
        assertThat(created.passwordHash()).isEqualTo("encoded-password");
        assertThat(created.displayName()).isEqualTo("operator");
        assertThat(created.role()).isEqualTo(UserRole.ADMIN);
        assertThat(created.enabled()).isTrue();
        assertThat(created.tokenVersion()).isZero();
    }

    @Test
    void duplicateUsernameCreateRetainsPostgresUniqueViolationSqlState() {
        userRepository.create("duplicate-user", "first-hash", "First user", UserRole.STUDENT);

        Throwable error = catchThrowable(() -> userRepository.create(
                "duplicate-user", "second-hash", "Second user", UserRole.STUDENT));

        assertThat(error).isInstanceOf(DuplicateKeyException.class);
        Throwable rootCause = error;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        assertThat(rootCause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23505");
    }

    @Test
    void bootstrapAdminPromotesAndReenablesExistingUserAndInvalidatesTokens() {
        insertUser("operator", "old-hash", "STUDENT", false, 7);

        assertThat(userRepository.bootstrapAdmin("operator", "encoded-password")).isTrue();

        UserAccount promoted = userRepository.findByUsername("operator").orElseThrow();
        assertThat(promoted.passwordHash()).isEqualTo("encoded-password");
        assertThat(promoted.displayName()).isEqualTo("Existing operator");
        assertThat(promoted.role()).isEqualTo(UserRole.ADMIN);
        assertThat(promoted.enabled()).isTrue();
        assertThat(promoted.tokenVersion()).isEqualTo(8);
    }

    @Test
    void bootstrapAdminLeavesConfiguredUserUntouchedWhenEnabledAdminExists() {
        insertUser("existing-admin", "admin-hash", "ADMIN", true, 3);
        insertUser("operator", "old-hash", "STUDENT", true, 5);

        assertThat(userRepository.bootstrapAdmin("operator", "encoded-password")).isFalse();

        UserAccount untouched = userRepository.findByUsername("operator").orElseThrow();
        assertThat(untouched.passwordHash()).isEqualTo("old-hash");
        assertThat(untouched.role()).isEqualTo(UserRole.STUDENT);
        assertThat(untouched.tokenVersion()).isEqualTo(5);
    }

    @Test
    void concurrentBootstrapCallsSerializeOnTheAdministratorRoleLock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        try {
            Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                acquireAdminRoleLock();
                lockHeld.countDown();
                await(releaseLock);
            }));
            assertThat(lockHeld.await(5, SECONDS)).isTrue();

            Future<Boolean> first = executor.submit(
                    () -> userRepository.bootstrapAdmin("operator-a", "hash-a"));
            Future<Boolean> second = executor.submit(
                    () -> userRepository.bootstrapAdmin("operator-b", "hash-b"));

            assertThatThrownBy(() -> first.get(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> second.get(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();
            lockHolder.get(5, SECONDS);
            List<Boolean> results = List.of(first.get(5, SECONDS), second.get(5, SECONDS));

            assertThat(results).containsExactlyInAnyOrder(true, false);
            assertThat(enabledAdministrators()).hasSize(1);
            assertThat(enabledAdministrators().getFirst().get("username"))
                    .isIn("operator-a", "operator-b");
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void governancePagingAndMutationsReturnSafeCurrentStateAndIncrementTokens() {
        insertUser("governed-admin", "admin-hash", "ADMIN", true, 2);
        insertUser("governed-student", "student-hash", "STUDENT", true, 5);
        UserAccount student = userRepository.findByUsername("governed-student").orElseThrow();

        var page = userRepository.findPage(1, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(UserAccount::username)
                .containsExactly("governed-student", "governed-admin");

        UserAccount updated = new TransactionTemplate(transactionManager).execute(status -> {
            userRepository.lockAdminRoleState();
            assertThat(userRepository.lockById(student.id())).contains(student);
            assertThat(userRepository.countEnabledAdmins()).isEqualTo(1);
            UserAccount promoted = userRepository.updateRoleAndIncrementTokenVersion(
                    student.id(), UserRole.ADMIN);
            UserAccount disabled = userRepository.updateEnabledAndIncrementTokenVersion(
                    student.id(), false);
            assertThat(promoted.tokenVersion()).isEqualTo(6);
            assertThat(disabled.tokenVersion()).isEqualTo(7);
            return userRepository.incrementTokenVersion(student.id());
        });

        assertThat(updated).isNotNull();
        assertThat(updated.role()).isEqualTo(UserRole.ADMIN);
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.tokenVersion()).isEqualTo(8);
    }

    private void acquireAdminRoleLock() {
        jdbcClient.sql(ADMIN_ROLE_LOCK_SQL)
                .query((rs, rowNum) -> Boolean.TRUE)
                .single();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release administrator role lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding administrator role lock", exception);
        }
    }

    private List<Map<String, Object>> enabledAdministrators() {
        return jdbcClient.sql("""
                        SELECT username
                        FROM users
                        WHERE role = 'ADMIN' AND enabled = TRUE
                        ORDER BY username
                        """)
                .query()
                .listOfRows();
    }

    private void insertUser(
            String username,
            String passwordHash,
            String role,
            boolean enabled,
            long tokenVersion
    ) {
        jdbcClient.sql("""
                        INSERT INTO users (
                            username, password_hash, display_name, role, enabled, token_version
                        )
                        VALUES (
                            :username, :passwordHash, :displayName, :role, :enabled, :tokenVersion
                        )
                        """)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("displayName", "Existing " + username)
                .param("role", role)
                .param("enabled", enabled)
                .param("tokenVersion", tokenVersion)
                .update();
    }
}

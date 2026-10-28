package xyz.v185v.collegeassistant.testsupport;

import java.util.List;

import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test-db")
@Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
public abstract class DatabaseTestSupport {

    private static final List<String> REQUIRED_ENVIRONMENT = List.of(
            "TEST_DB_URL",
            "TEST_DB_USERNAME",
            "TEST_DB_PASSWORD",
            "JWT_SECRET");

    @DynamicPropertySource
    protected static void verifyIntegrationEnvironment(
            DynamicPropertyRegistry ignored
    ) {
        requireIntegrationEnvironment();
    }

    static void requireIntegrationEnvironment() {
        List<String> missing = REQUIRED_ENVIRONMENT.stream()
                .filter(name -> {
                    String value = System.getenv(name);
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "PostgreSQL integration tests require nonblank environment variables: "
                            + String.join(", ", missing)
                            + ". Use only a disposable database because tests truncate its schema.");
        }
    }
}

package xyz.v185v.collegeassistant.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class UserTokenStateMigrationContractTest {

    private static final String V1_PATH = "db/migration/V1__core_users_profile.sql";
    private static final String V10_PATH = "db/migration/V10__user_token_state.sql";
    private static final String ORIGINAL_V1_SHA256 =
            "0780b9329074c5de10bb9d1bc4b81b73cbb8c81968fd49db015f7e27dd805541";

    @Test
    void v10AddsEnabledAndNonNegativeTokenVersionWithoutRewritingUsers() throws Exception {
        String sql = resourceText(V10_PATH);
        String normalized = sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);

        assertThat(normalized)
                .contains("ALTER TABLE USERS ADD COLUMN ENABLED BOOLEAN NOT NULL DEFAULT TRUE, "
                        + "ADD COLUMN TOKEN_VERSION BIGINT NOT NULL DEFAULT 0")
                .contains("ADD CONSTRAINT CK_USERS_TOKEN_VERSION_NON_NEGATIVE "
                        + "CHECK (TOKEN_VERSION >= 0)")
                .doesNotContain("DROP TABLE", "CREATE TABLE USERS");
    }

    @Test
    void v1RemainsByteEquivalentAfterLineEndingNormalization() throws Exception {
        String normalizedV1 = resourceText(V1_PATH).replace("\r\n", "\n");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizedV1.getBytes(StandardCharsets.UTF_8));

        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(ORIGINAL_V1_SHA256);
    }

    private String resourceText(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        assertThat(resource.exists()).as("Flyway migration %s must exist", path).isTrue();
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}

package xyz.v185v.collegeassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ModelConfigurationMigrationContractTest {

    private static final String MIGRATION_PATH =
            "db/migration/V14__model_configuration_and_embedding_versions.sql";

    @Test
    void v14DefinesConstrainedBootstrapModelsAndVersionedVectorStorage() throws Exception {
        ClassPathResource migration = new ClassPathResource(MIGRATION_PATH);
        assertThat(migration.exists())
                .as("Flyway migration %s must exist", MIGRATION_PATH)
                .isTrue();

        String sql = normalize(migration.getContentAsString(StandardCharsets.UTF_8));

        assertThat(sql)
                .contains("CREATE TABLE MODEL_CONFIGURATION")
                .contains("CONFIGURATION_KEY VARCHAR(128) NOT NULL UNIQUE")
                .contains("MODEL_KIND VARCHAR(16) NOT NULL")
                .contains("API_KEY_CIPHERTEXT BYTEA")
                .contains("API_KEY_IV BYTEA")
                .contains("ENCRYPTION_KEY_ID VARCHAR(128)")
                .contains("STATUS VARCHAR(16) NOT NULL DEFAULT 'PENDING'")
                .contains("CREATED_BY BIGINT REFERENCES USERS(ID) ON DELETE SET NULL")
                .contains("'CHAT', 'EMBEDDING'")
                .contains("'ACTIVE', 'PENDING', 'FAILED', 'RETIRED'")
                .contains("'BOOTSTRAP-CHAT', 'CHAT'")
                .contains("'BOOTSTRAP-EMBEDDING', 'EMBEDDING'")
                .contains("CREATE UNIQUE INDEX UX_MODEL_CONFIGURATION_ACTIVE")
                .contains("ON MODEL_CONFIGURATION(MODEL_KIND) WHERE STATUS = 'ACTIVE'")
                .contains("CREATE UNIQUE INDEX UX_MODEL_CONFIGURATION_PENDING")
                .contains("ON MODEL_CONFIGURATION(MODEL_KIND) WHERE STATUS = 'PENDING'")
                .contains("CREATE FUNCTION ACTIVE_EMBEDDING_CONFIG_ID()")
                .contains("ALTER SEQUENCE EMBEDDING_CHUNK_ID_SEQ OWNED BY NONE")
                .contains("CREATE TABLE EMBEDDING_CHUNK_V14")
                .contains("EMBEDDING_CONFIG_ID BIGINT NOT NULL DEFAULT ACTIVE_EMBEDDING_CONFIG_ID()")
                .contains("PRIMARY KEY (EMBEDDING_CONFIG_ID, ID)")
                .contains("UNIQUE (EMBEDDING_CONFIG_ID, DOC_REF_TYPE, DOC_REF_ID, CHUNK_INDEX)")
                .contains("FOREIGN KEY (EMBEDDING_CONFIG_ID) REFERENCES MODEL_CONFIGURATION(ID)")
                .contains("PARTITION BY LIST (EMBEDDING_CONFIG_ID)")
                .contains("CREATE INDEX IDX_EMBEDDING_CHUNK_V14_HNSW")
                .contains("USING HNSW (EMBEDDING VECTOR_COSINE_OPS)")
                .contains("LEGACY_HASH")
                .contains("MIGRATED_HASH")
                .contains("RAISE EXCEPTION")
                .contains("ALTER TABLE EMBEDDING_CHUNK RENAME TO EMBEDDING_CHUNK_V13")
                .contains("ALTER TABLE EMBEDDING_CHUNK_V14 RENAME TO EMBEDDING_CHUNK")
                .contains("ALTER SEQUENCE EMBEDDING_CHUNK_ID_SEQ OWNED BY EMBEDDING_CHUNK.ID")
                .contains("DROP TABLE EMBEDDING_CHUNK_V13");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}

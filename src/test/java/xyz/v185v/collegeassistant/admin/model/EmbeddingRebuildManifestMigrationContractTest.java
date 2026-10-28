package xyz.v185v.collegeassistant.admin.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class EmbeddingRebuildManifestMigrationContractTest {

    private static final String MIGRATION_PATH =
            "db/migration/V15__embedding_rebuild_manifest.sql";

    @Test
    void definesAJobScopedImmutableTargetSnapshotWithOptionalCompletionProofs() throws Exception {
        ClassPathResource migration = new ClassPathResource(MIGRATION_PATH);

        assertThat(migration.exists()).isTrue();

        String sql = migration.getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
        assertThat(sql)
                .contains("CREATE TABLE EMBEDDING_REBUILD_MANIFEST_ITEM")
                .contains("JOB_ID BIGINT NOT NULL REFERENCES ADMIN_JOB(ID) ON DELETE CASCADE")
                .contains("TARGET_TYPE VARCHAR(16) NOT NULL")
                .contains("TARGET_ID BIGINT NOT NULL")
                .contains("CONTENT_VERSION VARCHAR(128) NOT NULL")
                .contains("CHUNK_COUNT INTEGER")
                .contains("CHUNK_TEXT_CHECKSUM VARCHAR(64)")
                .contains("PRIMARY KEY (JOB_ID, TARGET_TYPE, TARGET_ID)")
                .contains("'DOCUMENT', 'UGC'")
                .contains("CHUNK_COUNT IS NULL AND CHUNK_TEXT_CHECKSUM IS NULL")
                .contains("CHUNK_COUNT >= 0");
    }
}

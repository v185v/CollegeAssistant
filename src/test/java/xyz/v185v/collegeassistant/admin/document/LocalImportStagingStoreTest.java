package xyz.v185v.collegeassistant.admin.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.v185v.collegeassistant.storage.StorageProperties;

class LocalImportStagingStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesOnlyUnderNumericRunAndItemSegments() throws Exception {
        LocalImportStagingStore store = new LocalImportStagingStore(
                new StorageProperties(tempDir.resolve("objects")));

        StagedImportObject staged = store.save(
                41L, 7L,
                new ByteArrayInputStream("handbook".getBytes(StandardCharsets.UTF_8)));

        assertThat(staged.runId()).isEqualTo(41L);
        assertThat(staged.itemId()).isEqualTo(7L);
        assertThat(staged.size()).isEqualTo(8L);
        assertThat(staged.relativeKey()).isEqualTo("import-staging/41/7");
        try (var input = store.open(41L, 7L)) {
            assertThat(input.readAllBytes())
                    .isEqualTo("handbook".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(Files.readString(tempDir.resolve("objects/import-staging/41/7")))
                .isEqualTo("handbook");
    }

    @Test
    void rejectsNonPositiveServerIdentifiersAndNeverAcceptsClientPaths() {
        LocalImportStagingStore store = new LocalImportStagingStore(
                new StorageProperties(tempDir.resolve("objects")));

        assertThatThrownBy(() -> store.save(
                0L, 1L, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(1L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsOnlyObjectsOlderThanCutoffAndDeleteIsIdempotent() throws Exception {
        LocalImportStagingStore store = new LocalImportStagingStore(
                new StorageProperties(tempDir.resolve("objects")));
        store.save(1L, 2L, new ByteArrayInputStream(new byte[] {1}));
        Path object = tempDir.resolve("objects/import-staging/1/2");
        Files.setLastModifiedTime(object, java.nio.file.attribute.FileTime.from(
                Instant.parse("2026-07-20T00:00:00Z")));

        assertThat(store.findOlderThan(Instant.parse("2026-07-21T00:00:00Z")))
                .extracting(StagedImportObject::relativeKey)
                .containsExactly("import-staging/1/2");
        assertThat(store.findOlderThan(Instant.parse("2026-07-19T00:00:00Z")))
                .isEmpty();

        store.delete(1L, 2L);
        store.delete(1L, 2L);
        assertThat(object).doesNotExist();
    }
}

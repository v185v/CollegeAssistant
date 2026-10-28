package xyz.v185v.collegeassistant.admin.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.v185v.collegeassistant.admin.job.AdminJobLease;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class LocalBackupFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void promotesOnlyTheExactLeaseAttemptAndReturnsOpaqueVerifiedMetadata() throws Exception {
        LocalBackupFileStore store = store(tempDir.resolve("backups"));
        AdminJobLease lease = lease(41L, 2, "00000000-0000-0000-0000-000000000041");
        Path attempt = store.allocateAttempt(lease);
        Files.writeString(attempt, "abc");

        assertThat(store.attemptSize(attempt, lease)).isEqualTo(3);
        StoredBackupArtifact artifact = store.promoteAttemptAtomically(attempt, lease);

        assertThat(attempt).doesNotExist();
        assertThat(artifact.objectKey())
                .isEqualTo("backup-job-41-attempt-2-lease-"
                        + "00000000-0000-0000-0000-000000000041.dump")
                .doesNotContain(tempDir.toString())
                .doesNotContain("\\");
        assertThat(artifact.sizeBytes()).isEqualTo(3);
        assertThat(artifact.sha256()).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        try (InputStream input = store.open(artifact.objectKey())) {
            assertThat(input.readAllBytes()).isEqualTo("abc".getBytes());
        }
    }

    @Test
    void rejectsTraversalAbsolutePathsAndNonServerGeneratedDeletionTargets() throws Exception {
        Path root = tempDir.resolve("backups");
        LocalBackupFileStore store = store(root);
        Path outside = tempDir.resolve("outside.dump");
        Files.writeString(outside, "must remain");

        assertStorageFailure(() -> store.resolveArtifact("../outside.dump"));
        assertStorageFailure(() -> store.resolveArtifact(outside.toString()));
        assertStorageFailure(() -> store.delete("../outside.dump"));
        assertStorageFailure(() -> store.deleteAttempt(outside));

        assertThat(outside).hasContent("must remain");
    }

    @Test
    void orphanCleanupKeepsReferencedArchivesAndDeletesOnlyOldOwnedKeys() throws Exception {
        LocalBackupFileStore store = store(tempDir.resolve("backups"));
        StoredBackupArtifact referenced = promote(
                store, lease(51L, 0, "00000000-0000-0000-0000-000000000051"), "one");
        StoredBackupArtifact orphan = promote(
                store, lease(52L, 0, "00000000-0000-0000-0000-000000000052"), "two");
        Instant old = Instant.parse("2026-07-01T00:00:00Z");
        Files.setLastModifiedTime(store.resolveArtifact(referenced.objectKey()), FileTime.from(old));
        Files.setLastModifiedTime(store.resolveArtifact(orphan.objectKey()), FileTime.from(old));

        int deleted = store.cleanupOrphans(
                Set.of(referenced.objectKey()), Instant.parse("2026-07-02T00:00:00Z"));

        assertThat(deleted).isEqualTo(1);
        assertThat(store.resolveArtifact(referenced.objectKey())).exists();
        assertThatThrownBy(() -> store.resolveArtifact(orphan.objectKey()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void configuredRootThatIsAFileIsReportedUnavailableWithoutLeakingItsPath() throws Exception {
        Path rootFile = tempDir.resolve("root-file");
        Files.writeString(rootFile, "not a directory");
        LocalBackupFileStore store = store(rootFile);

        assertThat(store.isAvailable()).isFalse();
    }

    private StoredBackupArtifact promote(
            LocalBackupFileStore store,
            AdminJobLease lease,
            String content
    ) throws Exception {
        Path attempt = store.allocateAttempt(lease);
        Files.writeString(attempt, content);
        return store.promoteAttemptAtomically(attempt, lease);
    }

    private LocalBackupFileStore store(Path root) {
        return new LocalBackupFileStore(new BackupProperties(
                root, tempDir.resolve("pg-dump"), tempDir.resolve("pg-restore"),
                "db", 5432, "college", "user", "password",
                Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1),
                1024, 256, 10_000, 7, Duration.ofHours(1)));
    }

    private AdminJobLease lease(long jobId, int attempt, String token) {
        return new AdminJobLease(jobId, attempt, "worker", UUID.fromString(token));
    }

    private void assertStorageFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.STORAGE_FAILED);
                    assertThat(exception.getMessage()).doesNotContain(tempDir.toString());
                });
    }
}

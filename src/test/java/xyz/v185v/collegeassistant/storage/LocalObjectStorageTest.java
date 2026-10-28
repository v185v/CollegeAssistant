package xyz.v185v.collegeassistant.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class LocalObjectStorageTest {

    @TempDir
    Path tempDir;

    private Path root;
    private LocalObjectStorage storage;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("storage-root");
        storage = new LocalObjectStorage(new StorageProperties(root));
    }

    @Test
    void constructingStorageDoesNotCreateRoot() {
        assertThat(root).doesNotExist();
    }

    @Test
    void availabilityProbeDoesNotTraverseDocumentObjects() throws IOException {
        Files.createDirectories(root.resolve("documents/nested-object"));

        assertThat(storage.isAvailable()).isTrue();
    }

    @Test
    void availabilityProbeReturnsFalseWhenConfiguredRootIsAFile() throws IOException {
        Path rootFile = tempDir.resolve("storage-root-file");
        Files.writeString(rootFile, "not a directory");
        LocalObjectStorage brokenStorage = new LocalObjectStorage(new StorageProperties(rootFile));

        assertThat(brokenStorage.isAvailable()).isFalse();
    }

    @Test
    void saveStoresBytesAndReturnsRelativeMetadata() throws IOException {
        byte[] content = "campus handbook".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.save("documents", "handbook.txt", new ByteArrayInputStream(content));

        assertThat(stored.objectKey())
                .matches("documents/[0-9a-f-]{36}-handbook\\.txt")
                .doesNotStartWith("/")
                .doesNotContain("\\");
        assertThat(stored.originalFilename()).isEqualTo("handbook.txt");
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(root.resolve(stored.objectKey()))).isEqualTo(content);
    }

    @Test
    void saveGeneratesUniqueObjectKeys() {
        byte[] content = "same source".getBytes(StandardCharsets.UTF_8);

        StoredObject first = storage.save("documents", "source.txt", new ByteArrayInputStream(content));
        StoredObject second = storage.save("documents", "source.txt", new ByteArrayInputStream(content));

        assertThat(first.objectKey()).isNotEqualTo(second.objectKey());
    }

    @Test
    void saveCollisionDoesNotDeleteExistingObject() throws IOException {
        LocalObjectStorage fixedKeyStorage = new LocalObjectStorage(
                new StorageProperties(root),
                () -> "fixed-key");
        Path existing = root.resolve("documents/fixed-key-source.txt");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "original");

        assertStorageFailure(() -> fixedKeyStorage.save(
                "documents",
                "source.txt",
                new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8))));

        assertThat(existing).hasContent("original");
    }

    @Test
    void saveDoesNotCloseCallerInputStream() {
        CloseTrackingInputStream input = new CloseTrackingInputStream("content".getBytes(StandardCharsets.UTF_8));

        storage.save("documents", "source.txt", input);

        assertThat(input.closed).isFalse();
    }

    @Test
    void openReturnsSavedBytes() throws IOException {
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);
        StoredObject stored = storage.save("documents", "source.txt", new ByteArrayInputStream(content));

        try (InputStream input = storage.open(stored.objectKey())) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void deleteRemovesSavedObjectAndIsIdempotent() {
        StoredObject stored = storage.save(
                "documents",
                "source.txt",
                new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        Path storedPath = root.resolve(stored.objectKey());

        storage.delete(stored.objectKey());
        storage.delete(stored.objectKey());

        assertThat(storedPath).doesNotExist();
    }

    @Test
    void deleteRejectsDirectoryTargetWithoutRemovingIt() throws IOException {
        Path directory = root.resolve("documents/subdir");
        Files.createDirectories(directory);

        assertStorageFailure(() -> storage.delete("documents/subdir"));

        assertThat(directory).isDirectory();
    }

    @Test
    void listReturnsDeterministicImmutableRelativeMetadata() throws IOException {
        var keys = List.of("z-key", "a-key").iterator();
        LocalObjectStorage fixedKeyStorage = new LocalObjectStorage(
                new StorageProperties(root), keys::next);
        StoredObject first = fixedKeyStorage.save(
                "documents", "first.txt", new ByteArrayInputStream(new byte[] {1, 2}));
        StoredObject second = fixedKeyStorage.save(
                "documents", "second.txt", new ByteArrayInputStream(new byte[] {3}));
        Instant firstModified = Instant.parse("2026-07-25T00:00:00Z");
        Instant secondModified = Instant.parse("2026-07-24T00:00:00Z");
        Files.setLastModifiedTime(root.resolve(first.objectKey()), FileTime.from(firstModified));
        Files.setLastModifiedTime(root.resolve(second.objectKey()), FileTime.from(secondModified));

        List<StoredObjectMetadata> inventory = fixedKeyStorage.list("documents");

        assertThat(inventory).containsExactly(
                new StoredObjectMetadata(second.objectKey(), 1L, secondModified),
                new StoredObjectMetadata(first.objectKey(), 2L, firstModified));
        assertThat(inventory).allSatisfy(metadata -> assertThat(metadata.objectKey())
                .startsWith("documents/")
                .doesNotContain("\\")
                .doesNotContain(root.toAbsolutePath().normalize().toString()));
        assertThatThrownBy(() -> inventory.add(inventory.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listMissingNamespaceIsEmptyAndDoesNotCreateStorageRoot() {
        assertThat(storage.list("documents")).isEmpty();
        assertThat(root).doesNotExist();
    }

    @Test
    void listIncludesLeaseDerivedNamespaceFamilyAndExcludesUnrelatedNamespaces() {
        var keys = List.of("base-key", "run-key", "job-key", "avatar-key").iterator();
        LocalObjectStorage fixedKeyStorage = new LocalObjectStorage(
                new StorageProperties(root), keys::next);
        StoredObject base = fixedKeyStorage.save(
                "documents", "base.txt", new ByteArrayInputStream(new byte[] {1}));
        StoredObject run = fixedKeyStorage.save(
                "documents-run-7-item-8-attempt-0-lease-token",
                "run.txt",
                new ByteArrayInputStream(new byte[] {2}));
        StoredObject job = fixedKeyStorage.save(
                "documents-job-9-item-10-attempt-0-lease-token",
                "job.txt",
                new ByteArrayInputStream(new byte[] {3}));
        fixedKeyStorage.save(
                "avatars", "avatar.png", new ByteArrayInputStream(new byte[] {4}));

        assertThat(fixedKeyStorage.list("documents"))
                .extracting(StoredObjectMetadata::objectKey)
                .containsExactly(base.objectKey(), job.objectKey(), run.objectKey());
    }

    @Test
    void saveRejectsEmptyNamespaceFilenameAndNullInput() {
        assertStorageFailure(() -> storage.save("", "source.txt", new ByteArrayInputStream(new byte[0])));
        assertStorageFailure(() -> storage.save("documents", "", new ByteArrayInputStream(new byte[0])));
        assertStorageFailure(() -> storage.save("documents", "source.txt", null));
    }

    @ParameterizedTest
    @MethodSource("invalidSegments")
    void saveRejectsUnsafeNamespace(String namespace) {
        assertStorageFailure(() -> storage.save(
                namespace,
                "source.txt",
                new ByteArrayInputStream(new byte[0])));
    }

    @ParameterizedTest
    @MethodSource("invalidSegments")
    void saveRejectsUnsafeFilename(String filename) {
        assertStorageFailure(() -> storage.save(
                "documents",
                filename,
                new ByteArrayInputStream(new byte[0])));
    }

    @ParameterizedTest
    @MethodSource("invalidObjectKeys")
    void openRejectsUnsafeObjectKey(String objectKey) {
        assertStorageFailure(() -> storage.open(objectKey));
    }

    @ParameterizedTest
    @MethodSource("invalidObjectKeys")
    void deleteRejectsUnsafeObjectKey(String objectKey) {
        assertStorageFailure(() -> storage.delete(objectKey));
    }

    @ParameterizedTest
    @MethodSource("invalidSegments")
    void listRejectsUnsafeNamespace(String namespace) {
        assertStorageFailure(() -> storage.list(namespace));
    }

    @ParameterizedTest
    @ValueSource(strings = {"documents", "a/b/c"})
    void openRejectsObjectKeysWithoutExactlyTwoSegments(String objectKey) throws IOException {
        Path invalidTarget = root.resolve(objectKey);
        Files.createDirectories(invalidTarget.getParent());
        Files.writeString(invalidTarget, "must not be opened");

        assertStorageFailure(() -> {
            try (InputStream ignored = storage.open(objectKey)) {
// 关闭意外返回的流，确保 RED 测试不会泄漏资源。
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"documents", "a/b/c"})
    void deleteRejectsObjectKeysWithoutExactlyTwoSegments(String objectKey) throws IOException {
        Path invalidTarget = root.resolve(objectKey);
        Files.createDirectories(invalidTarget.getParent());
        Files.writeString(invalidTarget, "must not be deleted");

        assertStorageFailure(() -> storage.delete(objectKey));

        assertThat(invalidTarget).exists();
    }

    @Test
    void openMissingObjectUsesStorageErrorWithoutLeakingRoot() {
        assertStorageFailure(() -> storage.open("documents/missing.txt"));
    }

    @Test
    void saveIoFailureUsesStorageErrorWithoutLeakingRoot() throws IOException {
        Path rootFile = tempDir.resolve("root-is-a-file");
        Files.writeString(rootFile, "not a directory");
        LocalObjectStorage brokenStorage = new LocalObjectStorage(new StorageProperties(rootFile));

        assertStorageFailure(
                () -> brokenStorage.save(
                        "documents",
                        "source.txt",
                        new ByteArrayInputStream(new byte[0])),
                rootFile);
    }

    @Test
    void saveRejectsSymlinkedNamespaceOutsideRootWhenSupported() throws IOException {
        Path outsideFile = createEscapeSymlink();

        assertStorageFailure(() -> storage.save(
                "escape",
                "new.txt",
                new ByteArrayInputStream("outside".getBytes(StandardCharsets.UTF_8))));
        assertThat(outsideFile.getParent().resolve("new.txt")).doesNotExist();
    }

    @Test
    void openRejectsSymlinkEscapeWhenSupported() throws IOException {
        createEscapeSymlink();

        assertStorageFailure(() -> storage.open("escape/secret.txt"));
    }

    @Test
    void deleteRejectsSymlinkEscapeWhenSupported() throws IOException {
        Path outsideFile = createEscapeSymlink();

        assertStorageFailure(() -> storage.delete("escape/secret.txt"));
        assertThat(outsideFile).exists();
    }

    @Test
    void listRejectsSymlinkedNamespaceOutsideRootWhenSupported() throws IOException {
        createEscapeSymlink();

        assertStorageFailure(() -> storage.list("escape"));
    }

    @Test
    void listRejectsSymlinkedObjectOutsideRootWhenSupported() throws IOException {
        Files.createDirectories(root.resolve("documents"));
        Path outsideFile = tempDir.resolve("outside-object.txt");
        Files.writeString(outsideFile, "secret");
        try {
            Files.createSymbolicLink(root.resolve("documents/linked-object"), outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, () -> "Symbolic links unavailable: "
                    + exception.getClass().getSimpleName());
        }

        assertStorageFailure(() -> storage.list("documents"));
    }

    private Path createEscapeSymlink() throws IOException {
        Files.createDirectories(root);
        Path outsideDirectory = tempDir.resolve("outside");
        Files.createDirectories(outsideDirectory);
        Path outsideFile = outsideDirectory.resolve("secret.txt");
        Files.writeString(outsideFile, "secret");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outsideDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, () -> "Symbolic links unavailable: " + exception.getClass().getSimpleName());
        }
        return outsideFile;
    }

    private void assertStorageFailure(ThrowingCallable operation) {
        assertStorageFailure(operation, root);
    }

    private void assertStorageFailure(ThrowingCallable operation, Path sensitiveRoot) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.STORAGE_FAILED);
                    assertThat(exception.getMessage()).doesNotContain(sensitiveRoot.toAbsolutePath().normalize().toString());
                });
    }

    private static Stream<String> invalidSegments() {
        return Stream.of(
                ".",
                "..",
                "../escape",
                "/absolute",
                "C:/absolute",
                "nested/path",
                "nested\\path",
                "..\\escape");
    }

    private static Stream<String> invalidObjectKeys() {
        return Stream.of(
                "",
                ".",
                "..",
                "../escape.txt",
                "documents/../escape.txt",
                "/absolute.txt",
                "C:/absolute.txt",
                "documents\\..\\escape.txt");
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private CloseTrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}

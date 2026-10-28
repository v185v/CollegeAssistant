package xyz.v185v.collegeassistant.admin.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.storage.StorageProperties;

/** 仅使用持久化任务编号和条目编号寻址的文件系统暂存区。 */
@Service
public class LocalImportStagingStore implements ImportStagingStore {

    private static final String NAMESPACE = "import-staging";
    private static final String FAILURE_MESSAGE = "Import staging operation failed";

    private final Path root;

    public LocalImportStagingStore(StorageProperties properties) {
        if (properties == null || properties.localRoot() == null) {
            throw failure(null);
        }
        root = properties.localRoot().toAbsolutePath().normalize().resolve(NAMESPACE);
    }

    @Override
    public StagedImportObject save(long runId, long itemId, InputStream input) {
        requireIds(runId, itemId);
        if (input == null) {
            throw failure(null);
        }
        Path target = resolve(runId, itemId);
        Path temporary = target.resolveSibling("." + itemId + "." + UUID.randomUUID() + ".tmp");
        try {
            prepareDirectory(target.getParent());
            try (OutputStream output = Files.newOutputStream(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                input.transferTo(output);
            }
            moveNew(temporary, target);
            ensureRegular(target);
            return metadata(runId, itemId, target);
        } catch (BusinessException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(temporary);
            throw failure(exception);
        }
    }

    @Override
    public InputStream open(long runId, long itemId) {
        requireIds(runId, itemId);
        Path target = resolve(runId, itemId);
        try {
            ensureRegular(target);
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public List<StagedImportObject> findOlderThan(Instant cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            ensureDirectory(root);
            List<StagedImportObject> result = new ArrayList<>();
            try (DirectoryStream<Path> runs = Files.newDirectoryStream(root)) {
                for (Path runDirectory : runs) {
                    Long runId = numericName(runDirectory);
                    if (runId == null || !Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    collectOldItems(cutoff, result, runDirectory, runId);
                }
            }
            result.sort(Comparator.comparingLong(StagedImportObject::runId)
                    .thenComparingLong(StagedImportObject::itemId));
            return List.copyOf(result);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(exception);
        }
    }

    @Override
    public void delete(long runId, long itemId) {
        requireIds(runId, itemId);
        Path target = resolve(runId, itemId);
        try {
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            ensureRegular(target);
            Files.delete(target);
            deleteEmpty(target.getParent());
            deleteEmpty(root);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(exception);
        }
    }

    private void collectOldItems(
            Instant cutoff,
            List<StagedImportObject> result,
            Path runDirectory,
            long runId
    ) throws IOException {
        try (DirectoryStream<Path> items = Files.newDirectoryStream(runDirectory)) {
            for (Path item : items) {
                Long itemId = numericName(item);
                if (itemId == null || !Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                StagedImportObject metadata = metadata(runId, itemId, item);
                if (metadata.createdAt().isBefore(cutoff)) {
                    result.add(metadata);
                }
            }
        }
    }

    private void prepareDirectory(Path runDirectory) throws IOException {
        Files.createDirectories(root);
        ensureDirectory(root);
        if (Files.exists(runDirectory, LinkOption.NOFOLLOW_LINKS)) {
            ensureDirectory(runDirectory);
        } else {
            Files.createDirectory(runDirectory);
        }
    }

    private void ensureDirectory(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(null);
        }
    }

    private void ensureRegular(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toAbsolutePath().normalize().startsWith(root)) {
            throw failure(null);
        }
    }

    private StagedImportObject metadata(long runId, long itemId, Path path) throws IOException {
        return new StagedImportObject(
                runId, itemId, Files.size(path),
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant(),
                NAMESPACE + "/" + runId + "/" + itemId);
    }

    private void moveNew(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private Path resolve(long runId, long itemId) {
        Path target = root.resolve(Long.toString(runId)).resolve(Long.toString(itemId)).normalize();
        if (!target.startsWith(root)) {
            throw failure(null);
        }
        return target;
    }

    private Long numericName(Path path) {
        try {
            long value = Long.parseLong(path.getFileName().toString());
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void deleteEmpty(Path directory) throws IOException {
        if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            if (!entries.iterator().hasNext()) {
                Files.delete(directory);
            }
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
// 保留原始失败，同时不暴露本地文件系统细节。
        }
    }

    private static void requireIds(long runId, long itemId) {
        if (runId <= 0 || itemId <= 0) {
            throw new IllegalArgumentException("runId and itemId must be positive");
        }
    }

    private static BusinessException failure(Throwable cause) {
        return cause == null
                ? new BusinessException(ErrorCode.STORAGE_FAILED, FAILURE_MESSAGE)
                : new BusinessException(ErrorCode.STORAGE_FAILED, FAILURE_MESSAGE, cause);
    }
}

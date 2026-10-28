package xyz.v185v.collegeassistant.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * 将对象保存在配置根目录下的本地文件系统实现。
 *
 * <p>存储根目录应由服务进程独占控制，不能允许不可信本地用户并发修改。
 * 本实现会拒绝请求路径穿越和静态符号链接逃逸，但无法防御路径校验与文件操作之间
 * 由恶意本地进程制造的 TOCTOU 竞态。</p>
 */
@Service
public class LocalObjectStorage implements ObjectStorage {

    private static final String STORAGE_ERROR_MESSAGE = "Storage operation failed";

    private final Path root;
    private final Supplier<String> keySupplier;

    @Autowired
    public LocalObjectStorage(StorageProperties properties) {
        this(properties, () -> UUID.randomUUID().toString());
    }

    LocalObjectStorage(StorageProperties properties, Supplier<String> keySupplier) {
        if (properties == null || properties.localRoot() == null || keySupplier == null) {
            throw storageFailure();
        }
        try {
            root = properties.localRoot().toAbsolutePath().normalize();
            this.keySupplier = keySupplier;
        } catch (RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                Path existingParent = root.getParent();
                while (existingParent != null
                        && Files.notExists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
                    existingParent = existingParent.getParent();
                }
                return existingParent != null
                        && Files.isDirectory(existingParent)
                        && Files.isWritable(existingParent);
            }
            if (!Files.isDirectory(root)) {
                return false;
            }
            try (DirectoryStream<Path> ignored = Files.newDirectoryStream(root)) {
                return true;
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public StoredObject save(String namespace, String filename, InputStream input) {
        validateSegment(namespace);
        validateSegment(filename);
        if (input == null) {
            throw storageFailure();
        }

        String objectId;
        try {
            objectId = keySupplier.get();
        } catch (RuntimeException exception) {
            throw storageFailure();
        }
        validateSegment(objectId);

        String storedFilename = objectId + "-" + filename;
        Path target = null;
        boolean targetCreated = false;
        try {
            Path namespaceDirectory = resolveSegment(namespace);
            target = namespaceDirectory.resolve(storedFilename).normalize();
            ensureLexicallyWithinRoot(target);

            Files.createDirectories(namespaceDirectory);
            Path realRoot = root.toRealPath();
            ensureExistingPathWithinRoot(namespaceDirectory, realRoot);
            ensureExistingPathWithinRoot(target, realRoot);

            try (OutputStream output = Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                targetCreated = true;
                input.transferTo(output);
            }
            Path realTarget = target.toRealPath();
            ensureRealPathWithinRoot(realTarget, realRoot);
            long size = Files.size(target);
            return new StoredObject(namespace + "/" + storedFilename, filename, size);
        } catch (BusinessException exception) {
            if (targetCreated) {
                deleteQuietly(target);
            }
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (targetCreated) {
                deleteQuietly(target);
            }
            throw storageFailure();
        }
    }

    @Override
    public InputStream open(String objectKey) {
        Path target = resolveObjectKey(objectKey);
        try {
            Path realRoot = root.toRealPath();
            ensureExistingPathWithinRoot(target, realRoot);
            if (!Files.isRegularFile(target)) {
                throw storageFailure();
            }
            return Files.newInputStream(target);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public void delete(String objectKey) {
        Path target = resolveObjectKey(objectKey);
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path realRoot = root.toRealPath();
            ensureExistingPathWithinRoot(target, realRoot);
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw storageFailure();
            }
            Files.deleteIfExists(target);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public List<StoredObjectMetadata> list(String namespace) {
        validateSegment(namespace);
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            Path realRoot = root.toRealPath();
            List<Path> namespaceDirectories;
            try (Stream<Path> rootEntries = Files.list(root)) {
                namespaceDirectories = rootEntries
                        .filter(path -> belongsToNamespaceFamily(
                                namespace, path.getFileName().toString()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }

            List<StoredObjectMetadata> inventory = new ArrayList<>();
            for (Path namespaceDirectory : namespaceDirectories) {
                ensureExistingPathWithinRoot(namespaceDirectory, realRoot);
                if (!Files.isDirectory(namespaceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    throw storageFailure();
                }
                String actualNamespace = namespaceDirectory.getFileName().toString();
                validateSegment(actualNamespace);
                try (Stream<Path> entries = Files.list(namespaceDirectory)) {
                    inventory.addAll(entries
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .map(path -> metadata(actualNamespace, path, realRoot))
                            .toList());
                }
            }
            return List.copyOf(inventory);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    private boolean belongsToNamespaceFamily(String namespace, String candidate) {
        return candidate.equals(namespace) || candidate.startsWith(namespace + "-");
    }

    private StoredObjectMetadata metadata(String namespace, Path path, Path realRoot) {
        try {
            ensureExistingPathWithinRoot(path, realRoot);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw storageFailure();
            }
            String filename = path.getFileName().toString();
            validateSegment(filename);
            return new StoredObjectMetadata(
                    namespace + "/" + filename,
                    Files.size(path),
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    private Path resolveSegment(String segment) {
        Path resolved = root.resolve(segment).normalize();
        ensureLexicallyWithinRoot(resolved);
        return resolved;
    }

    private Path resolveObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.indexOf('\\') >= 0
                || objectKey.indexOf('\0') >= 0 || hasWindowsDrivePrefix(objectKey)) {
            throw storageFailure();
        }

        String[] segments = objectKey.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw storageFailure();
            }
        }

        try {
            Path relative = Path.of(objectKey);
            if (relative.isAbsolute() || relative.getNameCount() != 2) {
                throw storageFailure();
            }
            Path resolved = root.resolve(relative).normalize();
            ensureLexicallyWithinRoot(resolved);
            return resolved;
        } catch (InvalidPathException | SecurityException exception) {
            throw storageFailure();
        }
    }

    private void validateSegment(String segment) {
        if (segment == null || segment.isBlank() || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0
                || segment.indexOf('\0') >= 0 || segment.equals(".") || segment.equals("..")
                || hasWindowsDrivePrefix(segment)) {
            throw storageFailure();
        }

        try {
            Path path = Path.of(segment);
            if (path.isAbsolute() || path.getNameCount() != 1) {
                throw storageFailure();
            }
        } catch (InvalidPathException | SecurityException exception) {
            throw storageFailure();
        }
    }

    private boolean hasWindowsDrivePrefix(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    private void ensureLexicallyWithinRoot(Path candidate) {
        if (!candidate.startsWith(root)) {
            throw storageFailure();
        }
    }

    private void ensureExistingPathWithinRoot(Path candidate, Path realRoot) throws IOException {
        Path existing = candidate;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            ensureRealPathWithinRoot(existing.toRealPath(), realRoot);
        }

        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            ensureRealPathWithinRoot(candidate.toRealPath(), realRoot);
        }
    }

    private void ensureRealPathWithinRoot(Path candidate, Path realRoot) {
        if (!candidate.startsWith(realRoot)) {
            throw storageFailure();
        }
    }

    private void deleteQuietly(Path target) {
        if (target == null) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException | RuntimeException ignored) {
            // 保留对调用方暴露的单一、无路径信息存储错误。
        }
    }

    private static BusinessException storageFailure() {
        return new BusinessException(ErrorCode.STORAGE_FAILED, STORAGE_ERROR_MESSAGE);
    }
}

package xyz.v185v.collegeassistant.admin.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.job.AdminJobLease;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 平面本地备份存储，仅接受服务器生成的租约作用域文件名。 */
@Component
public class LocalBackupFileStore implements BackupFileStore {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern FINAL_KEY = Pattern.compile(
            "backup-job-[1-9][0-9]*-attempt-[0-9]+-lease-"
                    + UUID_PATTERN + "\\.dump");
    private static final Pattern PARTIAL_KEY = Pattern.compile(
            "backup-job-[1-9][0-9]*-attempt-[0-9]+-lease-"
                    + UUID_PATTERN + "\\.partial");
    private static final Pattern PROBE_KEY = Pattern.compile(
            "capability-" + UUID_PATTERN + "\\.probe");
    private static final String STORAGE_FAILURE = "Backup storage operation failed";

    private final Path root;

    public LocalBackupFileStore(BackupProperties properties) {
        if (properties == null || properties.root() == null) {
            throw storageFailure();
        }
        root = properties.root().toAbsolutePath().normalize();
    }

    @Override
    public boolean isAvailable() {
        Path probe = null;
        try {
            Path realRoot = ensureRoot();
            probe = root.resolve("capability-" + UUID.randomUUID() + ".probe");
            ensureLexicallyOwned(probe);
            Files.write(probe, new byte[0], StandardOpenOption.CREATE_NEW);
            ensureExistingOwned(probe, realRoot);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException | RuntimeException ignored) {
// 可用性已经确定。
                }
            }
        }
    }

    @Override
    public Path allocateAttempt(AdminJobLease lease) {
        requireLease(lease);
        try {
            ensureRoot();
            Path attempt = root.resolve(partialKey(lease)).normalize();
            ensureLexicallyOwned(attempt);
            if (Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) {
                throw storageFailure();
            }
            return attempt;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public long attemptSize(Path attempt, AdminJobLease lease) {
        Path owned = requireAttempt(attempt, lease);
        try {
            Path realRoot = ensureRoot();
            ensureRegularOwned(owned, realRoot);
            return noFollowSize(owned);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public StoredBackupArtifact promoteAttemptAtomically(
            Path attempt,
            AdminJobLease lease
    ) {
        Path owned = requireAttempt(attempt, lease);
        try {
            Path realRoot = ensureRoot();
            ensureRegularOwned(owned, realRoot);
            long size = noFollowSize(owned);
            String sha256 = sha256(owned);
            Path target = root.resolve(finalKey(lease)).normalize();
            ensureLexicallyOwned(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw storageFailure();
            }
            try {
                Files.move(owned, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw storageFailure();
            }
            ensureRegularOwned(target, realRoot);
            return new StoredBackupArtifact(target.getFileName().toString(), size, sha256);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public Path resolveArtifact(String objectKey) {
        if (!isFinalKey(objectKey)) {
            throw storageFailure();
        }
        try {
            Path realRoot = ensureRoot();
            Path artifact = root.resolve(objectKey).normalize();
            ensureLexicallyOwned(artifact);
            ensureRegularOwned(artifact, realRoot);
            return artifact;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public StoredBackupArtifact inspect(String objectKey) {
        Path artifact = resolveArtifact(objectKey);
        try {
            return new StoredBackupArtifact(
                    objectKey, noFollowSize(artifact), sha256(artifact));
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public InputStream open(String objectKey) {
        Path artifact = resolveArtifact(objectKey);
        try {
            return Files.newInputStream(
                    artifact, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public void delete(String objectKey) {
        Path artifact;
        try {
            if (!isFinalKey(objectKey)) {
                throw storageFailure();
            }
            artifact = root.resolve(objectKey).normalize();
            ensureLexicallyOwned(artifact);
            deleteOwned(artifact);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public void deleteAttempt(Path attempt) {
        try {
            if (attempt == null || attempt.getFileName() == null
                    || !PARTIAL_KEY.matcher(attempt.getFileName().toString()).matches()) {
                throw storageFailure();
            }
            Path owned = attempt.toAbsolutePath().normalize();
            ensureLexicallyOwned(owned);
            deleteOwned(owned);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public long usableSpaceBytes() {
        try {
            ensureRoot();
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public long totalSizeBytes() {
        try {
            Path realRoot = ensureRoot();
            long total = 0;
            try (Stream<Path> entries = Files.list(root)) {
                for (Path entry : entries.toList()) {
                    ensureRegularOwned(entry, realRoot);
                    long size = noFollowSize(entry);
                    total = Long.MAX_VALUE - total < size ? Long.MAX_VALUE : total + size;
                }
            }
            return total;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    @Override
    public int cleanupOrphans(Set<String> referencedKeys, Instant olderThan) {
        if (referencedKeys == null || olderThan == null) {
            throw new IllegalArgumentException("Orphan cleanup inputs are required");
        }
        try {
            Path realRoot = ensureRoot();
            int deleted = 0;
            try (Stream<Path> entries = Files.list(root)) {
                for (Path entry : entries.toList()) {
                    ensureRegularOwned(entry, realRoot);
                    String key = entry.getFileName().toString();
                    boolean ownedKey = FINAL_KEY.matcher(key).matches()
                            || PARTIAL_KEY.matcher(key).matches()
                            || PROBE_KEY.matcher(key).matches();
                    if (ownedKey && !referencedKeys.contains(key)
                            && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                                    .toInstant().isBefore(olderThan)) {
                        Files.delete(entry);
                        deleted++;
                    }
                }
            }
            return deleted;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw storageFailure();
        }
    }

    private void deleteOwned(Path target) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)
                || Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path realRoot = ensureRoot();
        ensureRegularOwned(target, realRoot);
        Files.delete(target);
    }

    private Path requireAttempt(Path attempt, AdminJobLease lease) {
        requireLease(lease);
        if (attempt == null) {
            throw storageFailure();
        }
        Path owned = attempt.toAbsolutePath().normalize();
        ensureLexicallyOwned(owned);
        if (!owned.getFileName().toString().equals(partialKey(lease))) {
            throw storageFailure();
        }
        return owned;
    }

    private Path ensureRoot() throws IOException {
        Files.createDirectories(root);
        if (!Files.isDirectory(root)) {
            throw storageFailure();
        }
        return root.toRealPath();
    }

    private void ensureRegularOwned(Path path, Path realRoot) throws IOException {
        ensureExistingOwned(path, realRoot);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw storageFailure();
        }
    }

    private void ensureExistingOwned(Path path, Path realRoot) throws IOException {
        ensureLexicallyOwned(path);
        if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(realRoot)) {
            throw storageFailure();
        }
    }

    private void ensureLexicallyOwned(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)
                || !path.toAbsolutePath().normalize().getParent().equals(root)) {
            throw storageFailure();
        }
    }

    private String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private long noFollowSize(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
    }

    private boolean isFinalKey(String key) {
        return key != null && FINAL_KEY.matcher(key).matches();
    }

    private String partialKey(AdminJobLease lease) {
        return keyStem(lease) + ".partial";
    }

    private String finalKey(AdminJobLease lease) {
        return keyStem(lease) + ".dump";
    }

    private String keyStem(AdminJobLease lease) {
        return "backup-job-" + lease.jobId()
                + "-attempt-" + lease.attempt()
                + "-lease-" + lease.leaseToken();
    }

    private void requireLease(AdminJobLease lease) {
        if (lease == null || lease.jobId() == null || lease.jobId() <= 0
                || lease.attempt() < 0 || lease.leaseToken() == null) {
            throw storageFailure();
        }
    }

    private BusinessException storageFailure() {
        return new BusinessException(ErrorCode.STORAGE_FAILED, STORAGE_FAILURE);
    }
}

package xyz.v185v.collegeassistant.admin.backup;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import xyz.v185v.collegeassistant.admin.job.AdminJobLease;

/** 存储操作仅允许使用服务器生成的令牌作用域备份键。 */
public interface BackupFileStore {

    boolean isAvailable();

    Path allocateAttempt(AdminJobLease lease);

    long attemptSize(Path attempt, AdminJobLease lease);

    StoredBackupArtifact promoteAttemptAtomically(Path attempt, AdminJobLease lease);

    Path resolveArtifact(String objectKey);

    StoredBackupArtifact inspect(String objectKey);

    InputStream open(String objectKey);

    void delete(String objectKey);

    void deleteAttempt(Path attempt);

    long usableSpaceBytes();

    long totalSizeBytes();

    int cleanupOrphans(Set<String> referencedKeys, Instant olderThan);
}

package xyz.v185v.collegeassistant.admin.backup.persistence;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface DatabaseBackupMapper extends BaseMapper<DatabaseBackupDO> {

    Long insertJobReturningId(@Param("actorId") Long actorId);
    Long insertBackupReturningId(@Param("jobId") Long jobId, @Param("actorId") Long actorId,
                                @Param("retentionDays") int retentionDays);
    int attachBackupToJob(@Param("jobId") Long jobId, @Param("backupId") Long backupId);
    long databaseSizeBytes();
    int markRunning(@Param("backupId") Long backupId, @Param("jobId") Long jobId,
                    @Param("attempt") int attempt, @Param("leaseToken") UUID leaseToken);
    Long publishReturning(@Param("backupId") Long backupId, @Param("jobId") Long jobId,
                          @Param("attempt") int attempt, @Param("leaseToken") UUID leaseToken,
                          @Param("storageKey") String storageKey, @Param("sha256") String sha256,
                          @Param("sizeBytes") long sizeBytes);
    int markFailed(@Param("backupId") Long backupId, @Param("jobId") Long jobId,
                   @Param("attempt") int attempt, @Param("leaseToken") UUID leaseToken,
                   @Param("summary") String summary);
    DatabaseBackupDO markValidatedReturning(@Param("backupId") Long backupId);
    DatabaseBackupDO markValidationFailedReturning(@Param("backupId") Long backupId);
    DatabaseBackupDO lockForDeletion(@Param("backupId") Long backupId);
    String findJobStatus(@Param("jobId") Long jobId);
    boolean tryAcquireRetentionLock();
    int reconcileTerminalJobs();
    DatabaseBackupDO lockNextExpired();
    int markDeleteFailed(@Param("backupId") Long backupId, @Param("summary") String summary);
}

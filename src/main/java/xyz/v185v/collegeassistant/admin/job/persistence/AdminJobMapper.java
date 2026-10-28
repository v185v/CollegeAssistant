package xyz.v185v.collegeassistant.admin.job.persistence;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AdminJobMapper extends BaseMapper<AdminJobDO> {

    AdminJobDO createJob(
            @Param("jobType") String jobType,
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId,
            @Param("requestedBy") Long requestedBy,
            @Param("requestPayloadJson") String requestPayloadJson);

    List<AdminJobItemDO> findItems(@Param("jobId") Long jobId);

    boolean hasConflictingEmbeddingRebuildWork();

    int cancelQueuedEmbeddingRebuild(@Param("configurationId") Long configurationId);

    int lockEmbeddingMaintenanceState();

    boolean hasBlockingEmbeddingRebuildForPendingConfiguration();

    AdminJobDO claimNext(
            @Param("workerId") String workerId,
            @Param("leaseToken") UUID leaseToken);

    int heartbeat(
            @Param("jobId") Long jobId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken);

    int finish(
            @Param("jobId") Long jobId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary);

    AdminJobDO retryFailed(@Param("jobId") Long jobId);

    int resetRetryableItems(@Param("jobId") Long jobId);

    List<AdminJobDO> expireStaleReturning(@Param("timeout") String timeout);

    AdminJobItemDO upsertItem(
            @Param("jobId") Long jobId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken,
            @Param("itemKey") String itemKey,
            @Param("itemType") String itemType,
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId);

    int checkpoint(
            @Param("jobId") Long jobId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken,
            @Param("itemId") Long itemId,
            @Param("status") String status,
            @Param("checkpointJson") String checkpointJson);

    Long lockAndAssertActive(
            @Param("jobId") Long jobId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken);

    boolean isActive(
            @Param("jobId") Long jobId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken);
}

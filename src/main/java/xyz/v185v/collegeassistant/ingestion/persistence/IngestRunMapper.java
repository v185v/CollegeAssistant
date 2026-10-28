package xyz.v185v.collegeassistant.ingestion.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface IngestRunMapper extends BaseMapper<IngestRunDO> {

    IngestRunDO startReturning(
            @Param("sourceId") Long sourceId,
            @Param("reason") String reason,
            @Param("workerId") String workerId,
            @Param("leaseToken") UUID leaseToken);

    IngestRunDO startLeaseReturning(
            @Param("sourceId") Long sourceId,
            @Param("reason") String reason,
            @Param("triggerType") String triggerType,
            @Param("scheduledAt") OffsetDateTime scheduledAt,
            @Param("workerId") String workerId,
            @Param("leaseToken") UUID leaseToken);

    IngestRunDO findRetryCandidateForUpdate(@Param("runId") Long runId);

    IngestRunDO retryFailedReturning(
            @Param("sourceId") Long sourceId,
            @Param("reason") String reason,
            @Param("attempt") int attempt,
            @Param("workerId") String workerId,
            @Param("leaseToken") UUID leaseToken);

    IngestRunDO retryFailedImportReturning(
            @Param("sourceId") Long sourceId,
            @Param("reason") String reason,
            @Param("attempt") int attempt,
            @Param("workerId") String workerId,
            @Param("leaseToken") UUID leaseToken);

    int copyFailedItems(
            @Param("newRunId") Long newRunId,
            @Param("failedRunId") Long failedRunId);

    int heartbeatLegacy(@Param("runId") Long runId);

    int heartbeatLease(
            @Param("runId") Long runId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken);

    IngestRunDO deferLegacyReturning(@Param("runId") Long runId);

    int deferLease(
            @Param("runId") Long runId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken);

    List<IngestRunDO> findDueDeferredForUpdate(
            @Param("now") OffsetDateTime now,
            @Param("batchSize") int batchSize);

    IngestRunDO claimDeferredReturning(
            @Param("runId") Long runId,
            @Param("workerId") String workerId,
            @Param("leaseToken") UUID leaseToken,
            @Param("now") OffsetDateTime now);

    int resetDueItems(
            @Param("runId") Long runId,
            @Param("now") OffsetDateTime now);

    IngestRunDO finishLegacyReturning(
            @Param("runId") Long runId,
            @Param("status") String status,
            @Param("added") long added,
            @Param("updated") long updated,
            @Param("skipped") long skipped,
            @Param("failed") long failed,
            @Param("errorDetail") String errorDetail);

    int finishLease(
            @Param("runId") Long runId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken,
            @Param("status") String status,
            @Param("added") long added,
            @Param("updated") long updated,
            @Param("skipped") long skipped,
            @Param("failed") long failed,
            @Param("errorCode") String errorCode,
            @Param("errorDetail") String errorDetail);

    List<IngestRunDO> expireStaleReturning(@Param("timeout") String timeout);

    List<IngestRunDO> expireStaleForSourceReturning(
            @Param("sourceId") Long sourceId,
            @Param("timeout") String timeout);

    Long lockAndAssertActive(
            @Param("runId") Long runId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken);

    boolean isActive(
            @Param("runId") Long runId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken);
}

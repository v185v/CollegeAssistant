package xyz.v185v.collegeassistant.ingestion.persistence;

import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemStatus;
import xyz.v185v.collegeassistant.ingestion.IngestRunLease;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.ScheduledIngestClaim;
import xyz.v185v.collegeassistant.ingestion.SourceScheduleCalculator;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunDO;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunMapper;

/**
* 持久化摄取运行队列。旧的仅编号方法仍供现有调用方使用，新的工作线程路径
* 使用尝试/令牌租约方法。
 */
@Repository
public class IngestRunRepository {

    private static final String LEGACY_WORKER_PREFIX = "legacy-ingest-";

    private final IngestRunMapper mapper;
    private final IngestRunItemRepository itemRepository;
    private final SourceRepository sourceRepository;

    public IngestRunRepository(
            IngestRunMapper mapper,
            IngestRunItemRepository itemRepository,
            SourceRepository sourceRepository
    ) {
        this.mapper = mapper;
        this.itemRepository = itemRepository;
        this.sourceRepository = sourceRepository;
    }

    /**
* 兼容启动路径。生产异步工作线程使用 {@link #startLease}，因此后续每次变更
* 都携带不透明的尝试/令牌能力凭证。
     */
    @Transactional
    public IngestRunDto start(Long sourceId, String reason) {
        requirePositive(sourceId, "sourceId");
        requireReason(reason);
        String workerId = LEGACY_WORKER_PREFIX + UUID.randomUUID();
        IngestRunDO row = mapper.startReturning(sourceId, reason, workerId, UUID.randomUUID());
        if (row == null) {
            throw activeRunConflict();
        }
        return mapRun(row);
    }

/** 在一个短事务中创建并立即租约化手动/计划/导入运行。 */
    @Transactional
    public IngestRunLease startLease(
            Long sourceId,
            String reason,
            String triggerType,
            String workerId
    ) {
        requirePositive(sourceId, "sourceId");
        requireReason(reason);
        requireTriggerType(triggerType);
        requireWorkerId(workerId);
        return tryStartLease(sourceId, reason, triggerType, null, workerId)
                .orElseThrow(this::activeRunConflict);
    }

/** 尝试获取计划租约，不因活动运行竞争而回滚。 */
    Optional<IngestRunLease> tryStartScheduledLease(
            Long sourceId,
            OffsetDateTime scheduledAt,
            String workerId
    ) {
        requirePositive(sourceId, "sourceId");
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt must not be null");
        }
        requireWorkerId(workerId);
        return tryStartLease(sourceId, "Scheduled ingestion", "SCHEDULED", scheduledAt, workerId);
    }

    private Optional<IngestRunLease> tryStartLease(
            Long sourceId,
            String reason,
            String triggerType,
            OffsetDateTime scheduledAt,
            String workerId
    ) {
        UUID token = UUID.randomUUID();
        return Optional.ofNullable(mapper.startLeaseReturning(
                        sourceId, reason, triggerType, scheduledAt, workerId, token))
                .map(this::mapLease);
    }

/** 返回全新的租约重试运行，仅填充旧运行中的失败条目。 */
    @Transactional
    public IngestRunLease retryFailed(Long failedRunId, String workerId) {
        return retryFailed(failedRunId, workerId, "RETRY", null);
    }

/** 重试本地上传，同时保留 IMPORT 作为导入历史的运行类型。 */
    @Transactional
    public IngestRunLease retryFailedImport(Long failedRunId, String workerId) {
        return retryFailed(failedRunId, workerId, "IMPORT", "IMPORT");
    }

    private IngestRunLease retryFailed(
            Long failedRunId,
            String workerId,
            String newTriggerType,
            String requiredPreviousTriggerType
    ) {
        requirePositive(failedRunId, "failedRunId");
        requireWorkerId(workerId);
        IngestRunDO failed = Optional.ofNullable(mapper.findRetryCandidateForUpdate(failedRunId))
                .orElseThrow(() -> retryUnavailable(failedRunId));
        if (requiredPreviousTriggerType != null
                && !requiredPreviousTriggerType.equals(failed.getTriggerType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only import runs can be retried here");
        }
        UUID token = UUID.randomUUID();
        IngestRunDO retryRow = "IMPORT".equals(newTriggerType)
                ? mapper.retryFailedImportReturning(
                        failed.getSourceId(), failed.getReason(), failed.getAttempt() + 1,
                        workerId, token)
                : mapper.retryFailedReturning(
                        failed.getSourceId(), failed.getReason(), failed.getAttempt() + 1,
                        workerId, token);
        IngestRunLease retry = Optional.ofNullable(retryRow)
                .map(this::mapLease)
                .orElseThrow(this::retryActiveRunConflict);
        mapper.copyFailedItems(retry.runId(), failedRunId);
        return retry;
    }

/** 为 V12 之前的同步调用方保留的旧心跳方法。 */
    public boolean heartbeat(Long runId) {
        requirePositive(runId, "runId");
        return mapper.heartbeatLegacy(runId) == 1;
    }

/** 后台工作线程和独立调度器使用的尝试/令牌 CAS 心跳。 */
    public boolean heartbeat(IngestRunLease lease) {
        return mapper.heartbeatLease(lease.runId(), lease.attempt(), lease.leaseToken()) == 1;
    }

/** 在 HTTP 429 后将旧运行尝试移动到持久化延迟状态。 */
    public IngestRunDto defer(Long runId, OffsetDateTime retryAt) {
        requirePositive(runId, "runId");
        if (retryAt == null) {
            throw new IllegalArgumentException("retryAt must not be null");
        }
        return Optional.ofNullable(mapper.deferLegacyReturning(runId))
                .map(this::mapRun)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INGEST_FAILED, "Ingestion run cannot be deferred"));
    }

/** 使用尝试/令牌 CAS 延迟，防止过期工作线程延迟更新的尝试。 */
    public boolean defer(IngestRunLease lease, OffsetDateTime retryAt) {
        if (lease == null || retryAt == null) {
            throw new IllegalArgumentException("Deferred lease arguments are invalid");
        }
        return mapper.deferLease(lease.runId(), lease.attempt(), lease.leaseToken()) == 1;
    }

    /**
* 在推进 source.next_run_at 的同一事务中认领到期数据源记录并创建带租约的 SCHEDULED 运行。
* 数据源记录锁与数据源删除共用。
     */
    @Transactional
    public List<ScheduledIngestClaim> claimDueScheduled(
            int batchSize,
            OffsetDateTime now,
            SourceScheduleCalculator scheduleCalculator,
            String workerId
    ) {
        requireBatchSize(batchSize);
        if (now == null || scheduleCalculator == null) {
            throw new IllegalArgumentException("Scheduled claim arguments are required");
        }
        requireWorkerId(workerId);
        List<ScheduledIngestClaim> claims = new ArrayList<>();
        for (SourceRecord source : sourceRepository.lockDueScheduled(now, batchSize)) {
            OffsetDateTime scheduledAt = source.nextRunAt();
            if (scheduledAt == null) {
                continue;
            }
            OffsetDateTime nextRunAt = scheduleCalculator.nextRunAt(source, now.toInstant());
            if (nextRunAt == null || !nextRunAt.isAfter(now)) {
                throw new IllegalStateException("Scheduled source did not produce a future run");
            }
            Optional<IngestRunLease> lease = tryStartScheduledLease(
                    source.id(), scheduledAt, workerId);
            if (lease.isEmpty()) {
                continue;
            }
            if (!sourceRepository.advanceScheduledRun(source.id(), nextRunAt, now)) {
                throw new IllegalStateException("Scheduled source changed while its row was locked");
            }
            IngestRunLease acceptedLease = lease.orElseThrow();
            IngestRunDto running = findById(acceptedLease.runId()).orElseThrow(() ->
                    new IllegalStateException("Scheduled ingestion run was not persisted"));
            claims.add(new ScheduledIngestClaim(source, running, acceptedLease, List.of()));
        }
        return List.copyOf(claims);
    }

    /**
* 为到期延迟运行重新签发尝试/令牌，不创建竞争的运行记录。
* 使用 SKIP LOCKED 锁定运行，使并发延迟调度器只能观察到一个获胜者。
     */
    @Transactional
    public List<ScheduledIngestClaim> claimDueDeferred(
            int batchSize,
            OffsetDateTime now,
            String workerId
    ) {
        requireBatchSize(batchSize);
        if (now == null) {
            throw new IllegalArgumentException("Deferred claim time is required");
        }
        requireWorkerId(workerId);
        List<IngestRunDO> candidates = mapper.findDueDeferredForUpdate(now, batchSize);
        List<ScheduledIngestClaim> claims = new ArrayList<>(candidates.size());
        for (IngestRunDO candidate : candidates) {
            SourceRecord source = sourceRepository.findById(candidate.getSourceId()).orElseThrow(() ->
                    new IllegalStateException("Deferred ingestion source no longer exists"));
            UUID token = UUID.randomUUID();
            Optional<IngestRunLease> lease = Optional.ofNullable(mapper.claimDeferredReturning(
                            candidate.getId(), workerId, token, now))
                    .map(this::mapLease);
            if (lease.isEmpty()) {
                continue;
            }
            IngestRunLease acceptedLease = lease.orElseThrow();
            List<IngestRunItemDto> retryItems = itemRepository.findByRunId(acceptedLease.runId())
                    .stream()
                    .filter(item -> item.status() == IngestRunItemStatus.FAILED)
                    .filter(item -> item.nextAttemptAt() != null && !item.nextAttemptAt().isAfter(now))
                    .toList();
            if (retryItems.isEmpty()) {
                throw new IllegalStateException("Deferred ingestion run has no due retry items");
            }
            int resetItems = mapper.resetDueItems(acceptedLease.runId(), now);
            if (resetItems != retryItems.size()) {
                throw new IllegalStateException("Deferred ingestion retry items changed during claim");
            }
            IngestRunDto running = findById(acceptedLease.runId()).orElseThrow(() ->
                    new IllegalStateException("Deferred ingestion run was not persisted"));
            claims.add(new ScheduledIngestClaim(source, running, acceptedLease, retryItems));
        }
        return List.copyOf(claims);
    }

/** 为现有同步调用方兼容性保留的旧终态转换。 */
    public IngestRunDto finish(
            Long runId,
            IngestRunStatus status,
            long added,
            long updated,
            long skipped,
            long failed,
            String errorDetail
    ) {
        requirePositive(runId, "runId");
        validateFinish(status, added, updated, skipped, failed);
        return Optional.ofNullable(mapper.finishLegacyReturning(
                        runId, status.name(), added, updated, skipped, failed, errorDetail))
                .map(this::mapRun)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INGEST_FAILED, "Ingestion run cannot be finished"));
    }

/** 尝试/令牌终态 CAS。过期工作线程得到 false，无法覆盖重试结果。 */
    public boolean finish(
            IngestRunLease lease,
            IngestRunStatus status,
            long added,
            long updated,
            long skipped,
            long failed,
            String errorCode,
            String errorDetail
    ) {
        validateFinish(status, added, updated, skipped, failed);
        return mapper.finishLease(
                lease.runId(), lease.attempt(), lease.leaseToken(), status.name(),
                added, updated, skipped, failed, errorCode, errorDetail) == 1;
    }

/** 将过期尝试标记为失败并清除令牌，然后手动重试才能创建新运行。 */
    public int expireStale(Duration timeout) {
        return expireStaleRuns(timeout).size();
    }

/** 返回所有全局过期记录，以便原子写入终态审计。 */
    public List<IngestRunDto> expireStaleRuns(Duration timeout) {
        validateTimeout(timeout);
        return mapper.expireStaleReturning(postgresInterval(timeout)).stream()
                .map(this::mapRun)
                .toList();
    }

/** 在原子替换认领前返回某个数据源的所有过期记录。 */
    public List<IngestRunDto> expireStaleRunsForSource(Long sourceId, Duration timeout) {
        requirePositive(sourceId, "sourceId");
        validateTimeout(timeout);
        return mapper.expireStaleForSourceReturning(sourceId, postgresInterval(timeout)).stream()
                .map(this::mapRun)
                .toList();
    }

/** 返回数据源是否存在仍拥有活动数据源唯一键的持久化运行。 */
    public boolean hasActiveForSource(Long sourceId) {
        requirePositive(sourceId, "sourceId");
        return mapper.exists(Wrappers.<IngestRunDO>lambdaQuery()
                .eq(IngestRunDO::getSourceId, sourceId)
                .in(IngestRunDO::getStatus, "RUNNING", "DEFERRED"));
    }

/** 读取数据源清单投影所需的最近运行状态。 */
    public Optional<IngestRunStatus> findLatestStatus(Long sourceId) {
        requirePositive(sourceId, "sourceId");
        return mapper.selectPage(new Page<IngestRunDO>(1, 1, false),
                        Wrappers.<IngestRunDO>lambdaQuery()
                                .select(IngestRunDO::getStatus)
                                .eq(IngestRunDO::getSourceId, sourceId)
                                .orderByDesc(IngestRunDO::getStartedAt, IngestRunDO::getId))
                .getRecords().stream().findFirst()
                .map(row -> IngestRunStatus.valueOf(row.getStatus()));
    }

/** 作为数据源删除的最后事务步骤移除历史运行状态。 */
    public int deleteForSource(Long sourceId) {
        requirePositive(sourceId, "sourceId");
        return mapper.delete(Wrappers.<IngestRunDO>lambdaQuery()
                .eq(IngestRunDO::getSourceId, sourceId));
    }

/** 现有管理运行历史接口使用的旧分页结果。 */
    public PageResult<IngestRunDto> find(
            Long sourceId,
            IngestRunStatus status,
            int page,
            int size
    ) {
        if (sourceId != null) {
            requirePositive(sourceId, "sourceId");
        }
        validatePage(page, size);
        return findPage(Wrappers.<IngestRunDO>lambdaQuery()
                .eq(sourceId != null, IngestRunDO::getSourceId, sourceId)
                .eq(status != null, IngestRunDO::getStatus, status == null ? null : status.name()),
                page, size);
    }

/** 仅分页本地上传运行历史，包括保留 IMPORT 触发类型的重试。 */
    public PageResult<IngestRunDto> findImports(int page, int size) {
        validatePage(page, size);
        return findPage(Wrappers.<IngestRunDO>lambdaQuery()
                .eq(IngestRunDO::getTriggerType, "IMPORT"), page, size);
    }

    public Optional<IngestRunDto> findById(Long runId) {
        requirePositive(runId, "runId");
        return Optional.ofNullable(mapper.selectOne(selectSummary(Wrappers.<IngestRunDO>lambdaQuery())
                .eq(IngestRunDO::getId, runId))).map(this::mapRun);
    }

/** 包含条目检查点的详细接口表示。 */
    public Optional<IngestRunDetailDto> findDetailById(Long runId) {
        requirePositive(runId, "runId");
        return Optional.ofNullable(mapper.selectOne(Wrappers.<IngestRunDO>lambdaQuery()
                .select(IngestRunDO::getId, IngestRunDO::getSourceId, IngestRunDO::getStatus,
                        IngestRunDO::getTriggerType, IngestRunDO::getScheduledAt, IngestRunDO::getDiscovered,
                        IngestRunDO::getAdded, IngestRunDO::getUpdated, IngestRunDO::getSkipped,
                        IngestRunDO::getFailed, IngestRunDO::getAttempt, IngestRunDO::getWorkerId,
                        IngestRunDO::getClaimedAt, IngestRunDO::getHeartbeatAt, IngestRunDO::getErrorCode,
                        IngestRunDO::getErrorDetail, IngestRunDO::getStartedAt, IngestRunDO::getFinishedAt)
                .eq(IngestRunDO::getId, runId))).map(this::mapDetail);
    }

/** 在保护事务中锁定运行记录，并重新检查完整租约谓词。 */
    public boolean lockAndAssertActive(IngestRunLease lease) {
        return mapper.lockAndAssertActive(
                lease.runId(), lease.attempt(), lease.leaseToken()) != null;
    }

    public boolean isActive(IngestRunLease lease) {
        return mapper.isActive(lease.runId(), lease.attempt(), lease.leaseToken());
    }

    private PageResult<IngestRunDto> findPage(LambdaQueryWrapper<IngestRunDO> query, int page, int size) {
        long total = mapper.selectCount(query);
        List<IngestRunDto> items = mapper.selectPage(new Page<IngestRunDO>(page, size, false),
                        selectSummary(query).orderByDesc(IngestRunDO::getStartedAt, IngestRunDO::getId))
                .getRecords().stream().map(this::mapRun).toList();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(items, total, page, size, pages);
    }

    private LambdaQueryWrapper<IngestRunDO> selectSummary(LambdaQueryWrapper<IngestRunDO> query) {
        return query.select(IngestRunDO::getId, IngestRunDO::getSourceId, IngestRunDO::getStatus,
                IngestRunDO::getAdded, IngestRunDO::getUpdated, IngestRunDO::getSkipped,
                IngestRunDO::getFailed, IngestRunDO::getErrorDetail,
                IngestRunDO::getStartedAt, IngestRunDO::getFinishedAt);
    }

    private IngestRunDto mapRun(IngestRunDO row) {
        return new IngestRunDto(
                row.getId(),
                row.getSourceId(),
                IngestRunStatus.valueOf(row.getStatus()),
                row.getAdded(),
                row.getUpdated(),
                row.getSkipped(),
                row.getFailed(),
                row.getErrorDetail(),
                row.getStartedAt(),
                row.getFinishedAt());
    }

    private IngestRunLease mapLease(IngestRunDO row) {
        return new IngestRunLease(
                row.getId(), row.getAttempt(), row.getWorkerId(), row.getLeaseToken());
    }

    private IngestRunDetailDto mapDetail(IngestRunDO row) {
        return new IngestRunDetailDto(
                row.getId(),
                row.getSourceId(),
                IngestRunStatus.valueOf(row.getStatus()),
                row.getTriggerType(),
                row.getScheduledAt(),
                row.getDiscovered(),
                row.getAdded(),
                row.getUpdated(),
                row.getSkipped(),
                row.getFailed(),
                row.getAttempt(),
                row.getWorkerId(),
                row.getClaimedAt(),
                row.getHeartbeatAt(),
                row.getErrorCode(),
                row.getErrorDetail(),
                row.getStartedAt(),
                row.getFinishedAt(),
                itemRepository.findByRunId(row.getId()));
    }

    private BusinessException retryUnavailable(Long runId) {
        if (findById(runId).isEmpty()) {
            return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Ingestion run not found");
        }
        return new BusinessException(
                ErrorCode.CONFLICT, "Only failed ingestion runs can be retried");
    }

    private BusinessException activeRunConflict() {
        return new BusinessException(
                ErrorCode.INGEST_ALREADY_RUNNING,
                "Ingestion is already running for this source");
    }

    private BusinessException retryActiveRunConflict() {
        return new BusinessException(
                ErrorCode.INGEST_ALREADY_RUNNING,
                "An active ingestion run already exists for this source");
    }

    private void validateFinish(
            IngestRunStatus status,
            long added,
            long updated,
            long skipped,
            long failed
    ) {
        if (status == null || status == IngestRunStatus.RUNNING
                || status == IngestRunStatus.DEFERRED) {
            throw new IllegalArgumentException("finish status must be terminal");
        }
        requireNonNegative(added, "added");
        requireNonNegative(updated, "updated");
        requireNonNegative(skipped, "skipped");
        requireNonNegative(failed, "failed");
    }

    private void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
    }

    private void requireReason(String reason) {
        if (reason != null && reason.length() > 200) {
            throw new IllegalArgumentException("reason must not exceed 200 characters");
        }
    }

    private void requireTriggerType(String triggerType) {
        if (!"MANUAL".equals(triggerType)
                && !"SCHEDULED".equals(triggerType)
                && !"IMPORT".equals(triggerType)
                && !"RETRY".equals(triggerType)) {
            throw new IllegalArgumentException("Unsupported ingestion trigger type");
        }
    }

    private void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must be nonblank and at most 128 characters");
        }
    }

    private void requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("claim batch size must be between 1 and 100");
        }
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private String postgresInterval(Duration duration) {
        return duration.toSeconds() + " seconds";
    }
}

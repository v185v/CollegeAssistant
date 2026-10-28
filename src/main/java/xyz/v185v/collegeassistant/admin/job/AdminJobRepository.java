package xyz.v185v.collegeassistant.admin.job;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.admin.job.persistence.AdminJobDO;
import xyz.v185v.collegeassistant.admin.job.persistence.AdminJobItemDO;
import xyz.v185v.collegeassistant.admin.job.persistence.AdminJobMapper;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 基于 PostgreSQL 的管理任务队列与比较并交换操作。 */
@Repository
public class AdminJobRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final AdminJobMapper mapper;
    private final ObjectMapper objectMapper;

    public AdminJobRepository(AdminJobMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

/** 创建一个排队任务，除非其部分活动唯一键已存在。 */
    public AdminJobDto create(
            AdminJobType type,
            String targetType,
            Long targetId,
            Long requestedBy,
            Map<String, Object> requestPayload
    ) {
        requireType(type);
        requireTargetType(targetType);
        if (requestedBy != null && requestedBy <= 0) {
            throw new IllegalArgumentException("requestedBy must be positive when present");
        }
        AdminJobDO created = mapper.createJob(
                type.name(), targetType, targetId, requestedBy, serializeJson(requestPayload));
        if (created == null) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "An active administration job already exists");
        }
        return toJobWithoutItems(created);
    }

/** 返回包含持久化条目检查点的任务详情。 */
    public Optional<AdminJobDto> findById(Long jobId) {
        requirePositive(jobId, "jobId");
        return Optional.ofNullable(mapper.selectById(jobId))
                .map(this::toJobWithoutItems)
                .map(job -> withItems(job, findItems(job.id())));
    }

/** 按最新优先列出向量重建尝试，包括为重试保留的终态尝试。 */
    public List<AdminJobDto> findEmbeddingRebuilds() {
        return List.copyOf(mapper.selectList(Wrappers.<AdminJobDO>lambdaQuery()
                        .eq(AdminJobDO::getJobType, "EMBEDDING_REBUILD")
                        .orderByDesc(AdminJobDO::getCreatedAt, AdminJobDO::getId)).stream()
                .map(this::toJobWithoutItems)
                .map(job -> withItems(job, findItems(job.id())))
                .toList());
    }

/** 返回某个候选配置最近一次重建尝试。 */
    public Optional<AdminJobDto> findLatestEmbeddingRebuild(long configurationId) {
        requirePositive(configurationId, "configurationId");
        return mapper.selectPage(new Page<AdminJobDO>(1, 1, false),
                        Wrappers.<AdminJobDO>lambdaQuery()
                                .eq(AdminJobDO::getJobType, "EMBEDDING_REBUILD")
                                .eq(AdminJobDO::getTargetId, configurationId)
                                .orderByDesc(AdminJobDO::getCreatedAt, AdminJobDO::getId))
                .getRecords().stream().findFirst()
                .map(this::toJobWithoutItems)
                .map(job -> withItems(job, findItems(job.id())));
    }

/** 当来源摄取或改变索引的管理任务处于活动状态时拒绝创建。 */
    public boolean hasConflictingEmbeddingRebuildWork() {
        return mapper.hasConflictingEmbeddingRebuildWork();
    }

/** 在工作线程认领前取消排队的候选重建任务。 */
    public boolean cancelQueuedEmbeddingRebuild(long configurationId) {
        requirePositive(configurationId, "configurationId");
        return mapper.cancelQueuedEmbeddingRebuild(configurationId) == 1;
    }

    /**
* 串行化开始、完成或放弃向量重建窗口的所有变更。
* 该锁的作用域是事务，因此必须由事务调用方获取。
     */
    public void lockEmbeddingMaintenanceState() {
        mapper.lockEmbeddingMaintenanceState();
    }

    /**
* PENDING 候选项从排队到激活或放弃前始终是写入栅栏，
* 包括之后可能重试的失败和部分完成终态尝试。
     */
    public boolean hasBlockingEmbeddingRebuildForPendingConfiguration() {
        return mapper.hasBlockingEmbeddingRebuildForPendingConfiguration();
    }

/** 原子认领最早的排队记录，不等待其他工作线程的记录锁。 */
    @Transactional
    public Optional<AdminJobLease> claimNext(String workerId) {
        requireWorkerId(workerId);
        UUID token = UUID.randomUUID();
        return Optional.ofNullable(mapper.claimNext(workerId, token))
                .map(this::toLease);
    }

/** 使用完整的尝试/令牌比较并交换谓词刷新租约。 */
    public boolean heartbeat(AdminJobLease lease) {
        return mapper.heartbeat(
                lease.jobId(), lease.attempt(), lease.leaseToken()) == 1;
    }

/** 仅当当前运行尝试仍拥有租约时写入终态。 */
    public boolean finish(
            AdminJobLease lease,
            AdminJobStatus status,
            String errorCode,
            String errorSummary
    ) {
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("Job finish status must be terminal");
        }
        return mapper.finish(
                lease.jobId(), lease.attempt(), lease.leaseToken(),
                status.name(), errorCode, errorSummary) == 1;
    }

    /**
* 仅重试管理员批准的终态结果。新尝试不带所有者和令牌，
* 已成功条目的检查点保持不变。
     */
    @Transactional
    public AdminJobDto retryFailed(Long jobId) {
        requirePositive(jobId, "jobId");
        AdminJobDO queued = mapper.retryFailed(jobId);
        if (queued == null) {
            if (findById(jobId).isEmpty()) {
                throw notFound();
            }
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Only failed administration jobs can be retried");
        }
        mapper.resetRetryableItems(jobId);
        return withItems(toJobWithoutItems(queued), findItems(jobId));
    }

/** 将过期运行任务标记为失败，并在手动重试前使其租约令牌失效。 */
    public int expireStale(Duration timeout) {
        return expireStaleJobs(timeout).size();
    }

/** 返回所有已变更记录，以便在同一事务中写入终态审计。 */
    public List<AdminJobDto> expireStaleJobs(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return List.copyOf(mapper.expireStaleReturning(timeout.toString()).stream()
                .map(this::toJobWithoutItems)
                .toList());
    }

/** 仅在给定任务租约仍有效时插入或恢复条目。 */
    public Optional<AdminJobItemDto> upsertItem(
            AdminJobLease lease,
            String itemKey,
            String itemType,
            String targetType,
            Long targetId
    ) {
        requireItem(itemKey, itemType);
        return Optional.ofNullable(mapper.upsertItem(
                        lease.jobId(), lease.attempt(), lease.leaseToken(),
                        itemKey, itemType, targetType, targetId))
                .map(this::toItem);
    }

/** 在受保护的任务事务中使用 CAS 写入任务条目检查点。 */
    boolean checkpoint(
            AdminJobLease lease,
            Long itemId,
            AdminJobStatus status,
            Map<String, Object> checkpoint
    ) {
        requirePositive(itemId, "itemId");
        if (status == null) {
            throw new IllegalArgumentException("item status must not be null");
        }
        return mapper.checkpoint(
                lease.jobId(), lease.attempt(), lease.leaseToken(), itemId,
                status.name(), serializeJson(checkpoint)) == 1;
    }

/** 在调用方执行事务绑定副作用前锁定并重新校验租约。 */
    boolean lockAndAssertActive(AdminJobLease lease) {
        return mapper.lockAndAssertActive(
                lease.jobId(), lease.attempt(), lease.leaseToken()) != null;
    }

    boolean isActive(AdminJobLease lease) {
        return mapper.isActive(lease.jobId(), lease.attempt(), lease.leaseToken());
    }

    private List<AdminJobItemDto> findItems(Long jobId) {
        return List.copyOf(mapper.findItems(jobId).stream()
                .map(this::toItem)
                .toList());
    }

    private AdminJobLease toLease(AdminJobDO job) {
        return new AdminJobLease(
                job.getId(), job.getAttempt(), job.getWorkerId(), job.getLeaseToken());
    }

    private AdminJobDto toJobWithoutItems(AdminJobDO job) {
        return new AdminJobDto(
                job.getId(),
                AdminJobType.valueOf(job.getJobType()),
                AdminJobStatus.valueOf(job.getStatus()),
                job.getTargetType(),
                job.getTargetId(),
                job.getRequestedBy(),
                job.getAttempt(),
                job.getWorkerId(),
                job.getLeaseToken(),
                job.getClaimedAt(),
                job.getHeartbeatAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getErrorCode(),
                job.getErrorSummary(),
                deserializeJson(job.getRequestPayloadJson()),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                List.of());
    }

    private AdminJobItemDto toItem(AdminJobItemDO item) {
        return new AdminJobItemDto(
                item.getId(),
                item.getJobId(),
                item.getItemKey(),
                item.getItemType(),
                item.getTargetType(),
                item.getTargetId(),
                AdminJobStatus.valueOf(item.getStatus()),
                item.getRetryCount(),
                item.getErrorCode(),
                item.getErrorSummary(),
                deserializeJson(item.getCheckpointJson()),
                item.getCheckpointAttempt(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }

    private AdminJobDto withItems(AdminJobDto job, List<AdminJobItemDto> items) {
        return new AdminJobDto(
                job.id(), job.jobType(), job.status(), job.targetType(), job.targetId(),
                job.requestedBy(), job.attempt(), job.workerId(), job.leaseToken(),
                job.claimedAt(), job.heartbeatAt(), job.startedAt(), job.finishedAt(),
                job.errorCode(), job.errorSummary(), job.requestPayload(), job.createdAt(),
                job.updatedAt(), items);
    }

    private String serializeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Job checkpoint must be serializable", exception);
        }
    }

    private Map<String, Object> deserializeJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(value, JSON_OBJECT));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored job checkpoint is invalid", exception);
        }
    }

    private void requireType(AdminJobType type) {
        if (type == null) {
            throw new IllegalArgumentException("job type must not be null");
        }
    }

    private void requireTargetType(String targetType) {
        if (targetType == null || targetType.isBlank() || targetType.length() > 64) {
            throw new IllegalArgumentException("targetType must be nonblank and at most 64 characters");
        }
    }

    private void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must be nonblank and at most 128 characters");
        }
    }

    private void requireItem(String itemKey, String itemType) {
        if (itemKey == null || itemKey.isBlank() || itemKey.length() > 512) {
            throw new IllegalArgumentException("itemKey must be nonblank and at most 512 characters");
        }
        if (itemType == null || itemType.isBlank() || itemType.length() > 64) {
            throw new IllegalArgumentException("itemType must be nonblank and at most 64 characters");
        }
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Administration job not found");
    }
}

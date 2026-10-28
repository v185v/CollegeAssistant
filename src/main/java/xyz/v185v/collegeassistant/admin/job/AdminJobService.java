package xyz.v185v.collegeassistant.admin.job;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 持久化管理任务的授权边界。 */
@Service
public class AdminJobService {

    private final AdminJobRepository repository;
    private final AdminAuditService auditService;

    @Autowired
    public AdminJobService(
            AdminJobRepository repository,
            AdminAuditService auditService
    ) {
        this.repository = repository;
        this.auditService = auditService;
    }

/** 为针对性任务测试保留的兼容构造函数。 */
    public AdminJobService(AdminJobRepository repository) {
        this(repository, null);
    }

    public AdminJobDto create(
            AuthPrincipal actor,
            AdminJobType type,
            String targetType,
            Long targetId,
            Map<String, Object> payload
    ) {
        requireAdmin(actor);
        return audited(
                requestEvent(actor.userId(), type, targetType, targetId),
                () -> repository.create(type, targetType, targetId, actor.userId(), payload));
    }

/** 内部调用方必须从受保护操作中传入已认证的操作者编号。 */
    public AdminJobDto create(
            AdminJobType type,
            String targetType,
            Long targetId,
            Long actorId,
            Map<String, Object> payload
    ) {
        return audited(
                requestEvent(actorId, type, targetType, targetId),
                () -> repository.create(type, targetType, targetId, actorId, payload));
    }

    public AdminJobDto get(AuthPrincipal actor, Long jobId) {
        requireAdmin(actor);
        return repository.findById(jobId).orElseThrow(() -> new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "Administration job not found"));
    }

    public AdminJobDto retry(AuthPrincipal actor, Long jobId) {
        requireAdmin(actor);
        return audited(
                AdminAuditEvent.requested(
                        actor.userId(), "ADMIN_JOB_RETRIED", "ADMIN_JOB", jobId,
                        Map.of("requested", true)),
                () -> repository.retryFailed(jobId));
    }

/** 应用一次租约保护的终态转换，并仅记录成功的 CAS 变更。 */
    public boolean finish(
            AdminJobLease lease,
            AdminJobStatus status,
            String errorCode,
            String errorSummary
    ) {
        AdminJobDto job = repository.findById(lease.jobId()).orElse(null);
        if (job == null) {
            return false;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobType", job.jobType().name());
        summary.put("status", status.name());
        if (errorCode != null && !errorCode.isBlank()) {
            summary.put("jobErrorCode", errorCode);
        }
        AdminAuditEvent event = AdminAuditEvent.requested(
                job.requestedBy(), "ADMIN_JOB_FINISHED", "ADMIN_JOB", job.id(), summary);
        if (status != AdminJobStatus.SUCCEEDED) {
            event = event.failed(ErrorCode.INTERNAL_ERROR);
        }
        if (auditService == null) {
            return repository.finish(lease, status, errorCode, errorSummary);
        }
        AdminAuditEvent terminal = event;
        return auditService.auditedOutcome(
                terminal,
                () -> repository.finish(lease, status, errorCode, errorSummary),
                Boolean.TRUE::equals);
    }

/** 使过期租约失效，并在一个事务中记录所有变更任务的终态。 */
    public int expireStale(Duration timeout) {
        Supplier<List<AdminJobDto>> expiration = () -> repository.expireStaleJobs(timeout);
        List<AdminJobDto> expired;
        if (auditService == null) {
            expired = expiration.get();
        } else {
            AdminAuditEvent failureTemplate = AdminAuditEvent.requested(
                    null, "ADMIN_JOB_EXPIRATION", "DATABASE", null, Map.of());
            expired = auditService.auditedMany(
                    failureTemplate,
                    expiration,
                    jobs -> jobs.stream().map(this::expiredEvent).toList());
        }
        return expired.size();
    }

    private AdminAuditEvent expiredEvent(AdminJobDto job) {
        return AdminAuditEvent.requested(
                        job.requestedBy(), "ADMIN_JOB_FINISHED", "ADMIN_JOB", job.id(),
                        Map.of(
                                "jobType", job.jobType().name(),
                                "status", AdminJobStatus.FAILED.name(),
                                "jobErrorCode", "LEASE_EXPIRED"))
                .failed(ErrorCode.INTERNAL_ERROR);
    }

    private AdminAuditEvent requestEvent(
            Long actorId,
            AdminJobType type,
            String targetType,
            Long targetId
    ) {
        return AdminAuditEvent.requested(
                actorId,
                type.name() + "_REQUESTED",
                targetType,
                targetId,
                Map.of("jobType", type.name()));
    }

    private <T> T audited(AdminAuditEvent event, Supplier<T> operation) {
        return auditService == null ? operation.get() : auditService.audited(event, operation);
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !UserRole.ADMIN.name().equals(principal.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role is required");
        }
    }
}

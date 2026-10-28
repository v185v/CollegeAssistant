package xyz.v185v.collegeassistant.ugc;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;

/**
 * UGC 应用服务，负责内容可见性、审核状态迁移及审核后索引事件发布。
 */
@Service
public class UgcService {

    private final UgcRepository ugcRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EmbeddingChunkRepository embeddingChunkRepository;
    private final AdminAuditService auditService;
    private EmbeddingMaintenanceGuard maintenanceGuard;

    @Autowired
    public UgcService(
            UgcRepository ugcRepository,
            ApplicationEventPublisher eventPublisher,
            EmbeddingChunkRepository embeddingChunkRepository,
            AdminAuditService auditService
    ) {
        this.ugcRepository = ugcRepository;
        this.eventPublisher = eventPublisher;
        this.embeddingChunkRepository = embeddingChunkRepository;
        this.auditService = auditService;
    }

/** 为针对性旧测试保留的兼容构造函数。 */
    public UgcService(UgcRepository ugcRepository, ApplicationEventPublisher eventPublisher) {
        this(ugcRepository, eventPublisher, null, null);
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

    /** 创建初始状态为待审核的 UGC。 */
    public UgcPostDto create(AuthPrincipal principal, CreateUgcPostRequest request) {
        return ugcRepository.create(principal.userId(), request);
    }

    /**
     * 分页查询 UGC；普通用户只能查看公开内容或自己的内容，管理员可按任意状态筛选。
     */
    public PageResult<UgcPostDto> list(
            AuthPrincipal principal,
            int page,
            int size,
            boolean mine,
            UgcStatus status,
            String tag
    ) {
        if (mine) {
            return ugcRepository.findPage(principal.userId(), status, tag, page, size);
        }
        UgcStatus visibleStatus = isAdmin(principal) ? status : UgcStatus.APPROVED;
        return ugcRepository.findPage(null, visibleStatus, tag, page, size);
    }

    /** 按公开、作者本人或管理员规则读取单条内容。 */
    public UgcPostDto get(AuthPrincipal principal, Long id) {
        UgcPostDto post = ugcRepository.findById(id).orElseThrow(this::notFound);
        boolean visible = post.status() == UgcStatus.APPROVED
                || Objects.equals(post.authorId(), principal.userId())
                || isAdmin(principal);
        if (!visible) {
            throw notFound();
        }
        return post;
    }

    /**
     * 将待审核内容原子迁移为通过或拒绝；通过事件仅在事务提交后触发索引。
     */
    @Transactional
    public UgcPostDto review(AuthPrincipal principal, Long id, ReviewUgcRequest request) {
        requireAdmin(principal);
        requireWritesAllowed();
        Map<String, Object> summary = request == null || request.decision() == null
                ? Map.of() : Map.of("decision", request.decision().name());
        return audited(
                AdminAuditEvent.requested(
                        principal.userId(), "UGC_REVIEWED", "UGC_POST", id, summary),
                () -> {
                    if (request == null || request.decision() == null) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_FAILED, "Review decision is required");
                    }
                    if (request.decision() == ReviewDecision.REJECT
                            && (request.reason() == null || request.reason().isBlank())) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_FAILED, "Rejection reason is required");
                    }

                    UgcPostDto post = ugcRepository.findById(id).orElseThrow(this::notFound);
                    if (post.status() != UgcStatus.PENDING) {
                        throw alreadyReviewed();
                    }

                    String reason = request.decision() == ReviewDecision.REJECT
                            ? request.reason() : null;
                    UgcPostDto reviewed = ugcRepository
                            .review(id, principal.userId(), request.decision(), reason)
                            .orElseThrow(this::alreadyReviewed);
                    if (request.decision() == ReviewDecision.APPROVE) {
                        eventPublisher.publishEvent(new UgcApprovedEvent(id));
                    }
                    return reviewed;
                });
    }

/** 在同一审计事务中移除帖子及其所有向量版本。 */
    @Transactional
    public UgcPostDto remove(AuthPrincipal principal, Long id) {
        requireAdmin(principal);
        requireWritesAllowed();
        return audited(
                AdminAuditEvent.requested(
                        principal.userId(), "UGC_REMOVED", "UGC_POST", id, Map.of()),
                () -> {
                    ugcRepository.findById(id).orElseThrow(this::notFound);
                    UgcPostDto removed = ugcRepository.remove(id).orElseThrow(this::notFound);
                    embeddingChunkRepository.deleteAllForReference("UGC", id);
                    return removed;
                });
    }

/** 将一条未关闭举报处理为允许列表中的终态。 */
    @Transactional
    public UgcReportDto resolveReport(
            AuthPrincipal principal,
            Long postId,
            Long reportId,
            UgcReportStatus status
    ) {
        requireAdmin(principal);
        Map<String, Object> summary = status == null
                ? Map.of("postId", postId)
                : Map.of("postId", postId, "status", status.name());
        return audited(
                AdminAuditEvent.requested(
                        principal.userId(), "UGC_REPORT_RESOLVED", "UGC_REPORT", reportId,
                        summary),
                () -> {
                    if (status != UgcReportStatus.RESOLVED
                            && status != UgcReportStatus.DISMISSED) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_FAILED,
                                "Report status must be RESOLVED or DISMISSED");
                    }
                    Optional<UgcReportDto> resolved = ugcRepository.resolveReport(
                            postId, reportId, principal.userId(), status);
                    if (resolved.isPresent()) {
                        return resolved.get();
                    }
                    if (ugcRepository.findReport(postId, reportId).isEmpty()) {
                        throw new BusinessException(
                                ErrorCode.RESOURCE_NOT_FOUND, "UGC report not found");
                    }
                    throw new BusinessException(
                            ErrorCode.CONFLICT, "UGC report has already been resolved");
                });
    }

/** 确保当前用户对已审核帖子仅有一条持久化点赞。 */
    @Transactional
    public UgcPostDto like(AuthPrincipal principal, Long id) {
        long userId = requireUserId(principal);
        requireWritesAllowed();
        ugcRepository.ensureLike(id, userId);
        return ugcRepository.findApprovedById(id).orElseThrow(this::notFound);
    }

/** 确保当前用户的首次举报及原因只持久化一次。 */
    @Transactional
    public void report(AuthPrincipal principal, Long id, ReportUgcRequest request) {
        long userId = requireUserId(principal);
        requireWritesAllowed();
        String reason = request.reason().strip();
        ugcRepository.ensureReport(id, userId, reason);
        ugcRepository.findApprovedById(id).orElseThrow(this::notFound);
    }

/** 验证目标帖子存在后列出可审计的举报详情。 */
    public PageResult<UgcReportDto> listReports(Long postId, int page, int size) {
        ugcRepository.findById(postId).orElseThrow(this::notFound);
        return ugcRepository.findReports(postId, page, size);
    }

    private boolean isAdmin(AuthPrincipal principal) {
        return principal != null && UserRole.ADMIN.name().equals(principal.role());
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (!isAdmin(principal)) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private <T> T audited(AdminAuditEvent event, Supplier<T> operation) {
        return auditService == null ? operation.get() : auditService.audited(event, operation);
    }

    private long requireUserId(AuthPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.UGC_NOT_FOUND, "UGC post not found");
    }

    private BusinessException alreadyReviewed() {
        return new BusinessException(ErrorCode.UGC_ALREADY_REVIEWED, "UGC post has already been reviewed");
    }
}

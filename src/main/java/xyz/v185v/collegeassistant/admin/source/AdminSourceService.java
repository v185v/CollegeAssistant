package xyz.v185v.collegeassistant.admin.source;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.SourceScheduleCalculator;

/** 面向管理员的数据源生命周期服务。 */
@Service
public class AdminSourceService {

    private static final String NONE = "NONE";
    private static final String DEFAULT_ZONE = "Asia/Shanghai";
    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final int DEFAULT_MAX_PAGES = 500;
    private static final int DEFAULT_MAX_CONCURRENCY = 4;

    private final SourceRepository sourceRepository;
    private final IngestionService ingestionService;
    private final AdminJobService jobService;
    private final SourcePreviewGateway previewGateway;
    private final SourceScheduleCalculator scheduleCalculator;
    private final Clock clock;
    private final AdminAuditService auditService;
    private EmbeddingMaintenanceGuard maintenanceGuard;

    @Autowired
    public AdminSourceService(
            SourceRepository sourceRepository,
            IngestionService ingestionService,
            AdminJobService jobService,
            SourcePreviewGateway previewGateway,
            SourceScheduleCalculator scheduleCalculator,
            AdminAuditService auditService
    ) {
        this(
                sourceRepository,
                ingestionService,
                jobService,
                previewGateway,
                scheduleCalculator,
                Clock.systemUTC(),
                auditService);
    }

/** 保留原调度依赖接口的兼容构造函数。 */
    public AdminSourceService(
            SourceRepository sourceRepository,
            IngestionService ingestionService,
            AdminJobService jobService,
            SourcePreviewGateway previewGateway,
            SourceScheduleCalculator scheduleCalculator
    ) {
        this(
                sourceRepository,
                ingestionService,
                jobService,
                previewGateway,
                scheduleCalculator,
                Clock.systemUTC(),
                null);
    }

/** 为针对性数据源服务测试保留的兼容构造函数。 */
    public AdminSourceService(
            SourceRepository sourceRepository,
            IngestionService ingestionService,
            AdminJobService jobService,
            SourcePreviewGateway previewGateway
    ) {
        this(
                sourceRepository,
                ingestionService,
                jobService,
                previewGateway,
                new SourceScheduleCalculator(),
                Clock.systemUTC(),
                null);
    }

    AdminSourceService(
            SourceRepository sourceRepository,
            IngestionService ingestionService,
            AdminJobService jobService,
            SourcePreviewGateway previewGateway,
            SourceScheduleCalculator scheduleCalculator,
            Clock clock
    ) {
        this(
                sourceRepository,
                ingestionService,
                jobService,
                previewGateway,
                scheduleCalculator,
                clock,
                null);
    }

    AdminSourceService(
            SourceRepository sourceRepository,
            IngestionService ingestionService,
            AdminJobService jobService,
            SourcePreviewGateway previewGateway,
            SourceScheduleCalculator scheduleCalculator,
            Clock clock,
            AdminAuditService auditService
    ) {
        this.sourceRepository = sourceRepository;
        this.ingestionService = ingestionService;
        this.jobService = jobService;
        this.previewGateway = previewGateway;
        this.scheduleCalculator = scheduleCalculator;
        this.clock = clock;
        this.auditService = auditService;
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

    public PageResult<SourceDto> list(AuthPrincipal actor, int page, int size) {
        requireAdmin(actor);
        PageResult<SourceRecord> records = sourceRepository.find(page, size);
        List<SourceDto> items = records.items().stream().map(this::toDto).toList();
        return new PageResult<>(items, records.total(), records.page(), records.size(), records.pages());
    }

    public SourceDto get(AuthPrincipal actor, Long sourceId) {
        requireAdmin(actor);
        return toDto(requireSource(sourceId));
    }

    public SourceDto create(AuthPrincipal actor, SourceRequest request) {
        requireAdmin(actor);
        AdminAuditEvent requested = AdminAuditEvent.requested(
                actor.userId(), "SOURCE_CREATED", "SOURCE", null, Map.of());
        Supplier<SourceDto> operation = () -> toDto(
                sourceRepository.create(toRecord(request, actor.userId(), null)));
        if (auditService == null) {
            return operation.get();
        }
        return auditService.auditedResult(
                requested, operation, created -> requested.withTargetId(created.id()));
    }

    @Transactional
    public SourceDto update(AuthPrincipal actor, Long sourceId, SourceRequest request) {
        requireAdmin(actor);
        return audited(
                AdminAuditEvent.requested(
                        actor.userId(), "SOURCE_UPDATED", "SOURCE", sourceId, Map.of()),
                () -> {
                    SourceRecord existing = lockMutable(sourceId);
                    SourceRecord candidate = toRecord(request, existing.createdBy(), existing);
                    return toDto(sourceRepository.update(sourceId, candidate).orElseThrow(() ->
                            conflict("Source cannot be updated while deletion is in progress")));
                });
    }

    @Transactional
    public SourceDto setEnabled(AuthPrincipal actor, Long sourceId, boolean enabled) {
        requireAdmin(actor);
        return audited(
                AdminAuditEvent.requested(
                        actor.userId(), "SOURCE_ENABLED_CHANGED", "SOURCE", sourceId,
                        Map.of("enabled", enabled)),
                () -> {
                    lockMutable(sourceId);
                    return toDto(sourceRepository.setEnabled(sourceId, enabled).orElseThrow(() ->
                            conflict("Source cannot be changed while deletion is in progress")));
                });
    }

/** 在锁定数据源变更记录期间认领删除并创建持久化删除任务。 */
    @Transactional
    public AdminJobDto requestDeletion(AuthPrincipal actor, Long sourceId) {
        requireAdmin(actor);
        requireWritesAllowed();
        lockMutable(sourceId);
        ingestionService.requireNoActiveRun(sourceId);
        if (!sourceRepository.markDeleting(sourceId)) {
            throw conflict("Source deletion is already in progress");
        }
        return jobService.create(
                actor, AdminJobType.SOURCE_DELETE, "SOURCE", sourceId, Map.of("sourceId", sourceId));
    }

    public List<SourcePreviewItem> test(AuthPrincipal actor, SourceRequest request) {
        requireAdmin(actor);
        return previewGateway.preview(toRecord(request, actor.userId(), null));
    }

    public List<SourcePreviewItem> preview(AuthPrincipal actor, Long sourceId) {
        requireAdmin(actor);
        SourceRecord source = requireSource(sourceId);
        if (!NONE.equals(source.deletionStatus())) {
            throw conflict("Source is being deleted");
        }
        return previewGateway.preview(source);
    }

    private SourceRecord lockMutable(Long sourceId) {
        SourceRecord source = sourceRepository.lockForMutation(sourceId).orElseThrow(() ->
                new BusinessException(ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
        if (!NONE.equals(source.deletionStatus())) {
            throw conflict("Source is being deleted");
        }
        return source;
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }

    private SourceRecord requireSource(Long sourceId) {
        return sourceRepository.findById(sourceId).orElseThrow(() ->
                new BusinessException(ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
    }

    private SourceRecord toRecord(
            SourceRequest request,
            Long createdBy,
            SourceRecord existing
    ) {
        if (request == null) {
            throw validation("Source request is required");
        }
        String name = text(request.name(), "Source name", 128);
        String type = text(request.type(), "Source type", 32).toUpperCase(Locale.ROOT);
        SourceConnectorType connectorType = require(request.connectorType(), "Source connector type");
        SourceScheduleType scheduleType = require(request.scheduleType(), "Source schedule type");
        if (connectorType == SourceConnectorType.LOCAL_UPLOAD
                && scheduleType != SourceScheduleType.MANUAL) {
            throw validation("Local upload sources must use manual scheduling");
        }
        String scheduleZone = request.scheduleZone() == null || request.scheduleZone().isBlank()
                ? DEFAULT_ZONE : text(request.scheduleZone(), "Source schedule zone", 64);

        String baseUrl = normalizeUrl(request.baseUrl(), connectorType);
        String cronExpression = normalizeSchedule(scheduleType, request.cronExpression(), scheduleZone);
        OffsetDateTime nextRunAt = scheduleCalculator.nextRunAt(
                scheduleType, cronExpression, scheduleZone, clock.instant());
        boolean enabled = request.enabled() == null || request.enabled();
        boolean sameHostOnly = request.sameHostOnly() == null || request.sameHostOnly();
        int maxDepth = defaulted(request.maxDepth(), DEFAULT_MAX_DEPTH);
        int maxPages = defaulted(request.maxPages(), DEFAULT_MAX_PAGES);
        int maxConcurrency = defaulted(request.maxConcurrency(), DEFAULT_MAX_CONCURRENCY);
        requireRange(maxDepth, 0, 5, "Source max depth");
        requireRange(maxPages, 1, 1000, "Source max pages");
        requireRange(maxConcurrency, 1, 8, "Source max concurrency");
        List<String> includePatterns = validatePatterns(request.includePatterns(), "includePatterns");
        List<String> excludePatterns = validatePatterns(request.excludePatterns(), "excludePatterns");

        return new SourceRecord(
                existing == null ? null : existing.id(),
                name,
                baseUrl,
                type,
                enabled,
                connectorType,
                existing == null ? NONE : existing.deletionStatus(),
                scheduleType,
                scheduleZone,
                cronExpression,
                sameHostOnly,
                maxDepth,
                maxPages,
                maxConcurrency,
                includePatterns,
                excludePatterns,
                nextRunAt,
                existing == null ? null : existing.lastRunAt(),
                createdBy,
                existing == null ? null : existing.createdAt(),
                existing == null ? null : existing.updatedAt());
    }

    private String normalizeUrl(String rawUrl, SourceConnectorType connectorType) {
        String candidate = rawUrl == null ? null : rawUrl.strip();
        if (connectorType == SourceConnectorType.LOCAL_UPLOAD) {
            if (candidate != null && !candidate.isBlank()) {
                throw validation("Local upload sources must not define a base URL");
            }
            return null;
        }
        if (candidate == null || candidate.isBlank()) {
            throw validation("Remote sources require an absolute HTTP(S) URL");
        }
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null) {
                throw validation("Remote sources require an absolute HTTP(S) URL");
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            throw validation("Remote source URL is invalid");
        }
    }

    private String normalizeSchedule(
            SourceScheduleType scheduleType,
            String rawCron,
            String scheduleZone
    ) {
        try {
            return scheduleCalculator.validateAndNormalize(
                    scheduleType, rawCron, scheduleZone, clock.instant());
        } catch (IllegalArgumentException exception) {
            throw validation(exception.getMessage());
        }
    }

    private List<String> validatePatterns(List<String> patterns, String field) {
        List<String> values = patterns == null ? List.of() : patterns.stream().map(value -> {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw validation("Source " + field + " contains an invalid pattern");
            }
            return value.strip();
        }).toList();
        if (values.size() > 100) {
            throw validation("Source " + field + " has too many patterns");
        }
        return List.copyOf(values);
    }

    private int defaulted(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw validation(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private SourceDto toDto(SourceRecord source) {
        Optional<IngestRunStatus> latest = ingestionService.findLatestStatus(source.id());
        return new SourceDto(
                source.id(),
                source.name(),
                source.type(),
                source.connectorType(),
                source.baseUrl(),
                source.enabled(),
                source.scheduleType(),
                source.scheduleZone(),
                source.cronExpression(),
                source.sameHostOnly(),
                source.maxDepth(),
                source.maxPages(),
                source.maxConcurrency(),
                source.includePatterns(),
                source.excludePatterns(),
                source.deletionStatus(),
                source.nextRunAt(),
                source.lastRunAt(),
                latest == null ? null : latest.orElse(null),
                source.createdAt(),
                source.updatedAt());
    }

    private <T> T audited(AdminAuditEvent event, Supplier<T> operation) {
        return auditService == null ? operation.get() : auditService.audited(event, operation);
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private <T> T require(T value, String field) {
        if (value == null) {
            throw validation(field + " is required");
        }
        return value;
    }

    private String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw validation(field + " is invalid");
        }
        return value.strip();
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}

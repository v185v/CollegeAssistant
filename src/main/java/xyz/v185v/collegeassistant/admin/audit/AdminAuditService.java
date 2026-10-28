package xyz.v185v.collegeassistant.admin.audit;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

/** 脱敏管理员变更与审计清单读取的事务边界。 */
@Service
public class AdminAuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditService.class);

    private final TransactionTemplate required;
    private final AdminAuditWriter writer;
    private final AdminAuditFailureWriter failureWriter;
    private final AdminAuditRedactor redactor;
    private final AdminAuditRepository repository;

    @Autowired
    public AdminAuditService(
            PlatformTransactionManager transactionManager,
            AdminAuditWriter writer,
            AdminAuditFailureWriter failureWriter,
            AdminAuditRedactor redactor,
            AdminAuditRepository repository
    ) {
        this(new TransactionTemplate(transactionManager), writer, failureWriter, redactor, repository);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    AdminAuditService(
            TransactionTemplate required,
            AdminAuditWriter writer,
            AdminAuditFailureWriter failureWriter,
            AdminAuditRedactor redactor
    ) {
        this(required, writer, failureWriter, redactor, null);
    }

    private AdminAuditService(
            TransactionTemplate required,
            AdminAuditWriter writer,
            AdminAuditFailureWriter failureWriter,
            AdminAuditRedactor redactor,
            AdminAuditRepository repository
    ) {
        this.required = Objects.requireNonNull(required, "required");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.failureWriter = Objects.requireNonNull(failureWriter, "failureWriter");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.repository = repository;
    }

    public <T> T audited(AdminAuditEvent requested, Supplier<T> domainOperation) {
        return executeMany(
                requested,
                domainOperation,
                ignored -> List.of(requested.succeeded()));
    }

/** 仅在变更确实改变状态时写入调用方指定的终态结果。 */
    public <T> T auditedOutcome(
            AdminAuditEvent outcome,
            Supplier<T> domainOperation,
            Predicate<T> shouldWrite
    ) {
        Objects.requireNonNull(shouldWrite, "shouldWrite");
        return executeMany(
                outcome,
                domainOperation,
                result -> shouldWrite.test(result) ? List.of(outcome) : List.of());
    }

/** 根据已提交的领域结果（例如生成的编号）派生成功事件。 */
    public <T> T auditedResult(
            AdminAuditEvent requested,
            Supplier<T> domainOperation,
            Function<T, AdminAuditEvent> successEvent
    ) {
        Objects.requireNonNull(successEvent, "successEvent");
        return executeMany(
                requested,
                domainOperation,
                result -> List.of(successEvent.apply(result).succeeded()));
    }

/** 在一次变更的同一事务中写入零个或多个由结果派生的事件。 */
    public <T> T auditedMany(
            AdminAuditEvent failureTemplate,
            Supplier<T> domainOperation,
            Function<T, List<AdminAuditEvent>> events
    ) {
        return executeMany(
                failureTemplate,
                domainOperation,
                Objects.requireNonNull(events, "events"));
    }

    private <T> T executeMany(
            AdminAuditEvent failureTemplate,
            Supplier<T> domainOperation,
            Function<T, List<AdminAuditEvent>> events
    ) {
        Objects.requireNonNull(domainOperation, "domainOperation");
        AdminAuditEvent safeFailure = redactor.redact(
                withCurrentRemoteAddress(failureTemplate));
        try {
            return required.execute(status -> {
                T result = domainOperation.get();
                List<AdminAuditEvent> derived = Objects.requireNonNull(
                        events.apply(result), "audit events");
                for (AdminAuditEvent event : derived) {
                    writer.writeInCurrentTransaction(redactor.redact(
                            withCurrentRemoteAddress(Objects.requireNonNull(event, "audit event"))));
                }
                return result;
            });
        } catch (RuntimeException exception) {
            try {
                failureWriter.writeFailure(safeFailure.failed(errorCodeOf(exception)));
            } catch (RuntimeException auditFailure) {
                exception.addSuppressed(auditFailure);
                LOGGER.error(
                        "Failure audit could not be stored: causeType={}",
                        auditFailure.getClass().getName());
            }
            throw exception;
        }
    }

    public PageResult<AdminAuditLogDto> list(
            AuthPrincipal actor,
            AdminAuditFilter filter,
            int page,
            int size
    ) {
        requireAdmin(actor);
        if (repository == null) {
            throw new IllegalStateException("Audit repository is unavailable");
        }
        if (page < 1 || size < 1 || size > 100) {
            throw validation("Invalid audit pagination");
        }
        AdminAuditFilter safeFilter = normalize(filter);
        return repository.find(safeFilter, page, size);
    }

    private AdminAuditEvent withCurrentRemoteAddress(AdminAuditEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.remoteAddress() != null) {
            return event;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return event.withRemoteAddress(attributes.getRequest().getRemoteAddr());
        }
        return event;
    }

    private AdminAuditFilter normalize(AdminAuditFilter filter) {
        AdminAuditFilter effective = filter == null
                ? new AdminAuditFilter(null, null, null, null, null, null, null)
                : filter;
        if (effective.actorId() != null && effective.actorId() <= 0) {
            throw validation("Audit actor filter is invalid");
        }
        if (effective.targetId() != null && effective.targetId() <= 0) {
            throw validation("Audit target filter is invalid");
        }
        String action = optionalText(effective.action(), 64, "Audit action filter is invalid");
        String targetType = optionalText(
                effective.targetType(), 32, "Audit target type filter is invalid");
        if (effective.createdFrom() != null && effective.createdTo() != null
                && effective.createdFrom().isAfter(effective.createdTo())) {
            throw validation("Audit time range is invalid");
        }
        return new AdminAuditFilter(
                effective.actorId(), action, targetType, effective.targetId(), effective.result(),
                effective.createdFrom(), effective.createdTo());
    }

    private String optionalText(String value, int maxLength, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw validation(message);
        }
        return normalized;
    }

    private int errorCodeOf(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getCode();
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}

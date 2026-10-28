package xyz.v185v.collegeassistant.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditResult;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class AdminJobServiceTest {

    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    private final AdminJobRepository repository = Mockito.mock(AdminJobRepository.class);
    private final AdminJobService service = new AdminJobService(repository);

    @Test
    void administratorCanReadAndManuallyRetryFailedJob() {
        AdminJobDto failed = job(AdminJobStatus.FAILED, 0);
        AdminJobDto retried = job(AdminJobStatus.QUEUED, 1);
        when(repository.findById(41L)).thenReturn(java.util.Optional.of(failed));
        when(repository.retryFailed(41L)).thenReturn(retried);

        assertThat(service.get(ADMIN, 41L)).isEqualTo(failed);
        assertThat(service.retry(ADMIN, 41L)).isEqualTo(retried);
    }

    @Test
    void nonAdministratorCannotReadOrRetryJobs() {
        assertThatThrownBy(() -> service.get(STUDENT, 41L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ADMIN_REQUIRED));
        assertThatThrownBy(() -> service.retry(STUDENT, 41L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ADMIN_REQUIRED));

        verify(repository, never()).findById(41L);
        verify(repository, never()).retryFailed(41L);
    }

    @Test
    void missingJobUsesThePublicNotFoundCode() {
        when(repository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.get(ADMIN, 99L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void jobCreationRetryAndTerminalTransitionUseAuditBoundaries() {
        AdminAuditService audit = passthroughAudit();
        AdminJobService auditedService = new AdminJobService(repository, audit);
        AdminJobDto running = job(AdminJobStatus.RUNNING, 0);
        AdminJobDto queued = job(AdminJobStatus.QUEUED, 1);
        AdminJobLease lease = new AdminJobLease(
                41L, 0, "worker", UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        when(repository.create(
                AdminJobType.DOCUMENT_REINDEX, "DOCUMENT", 7L, 1L, Map.of("documentId", 7L)))
                .thenReturn(running);
        when(repository.retryFailed(41L)).thenReturn(queued);
        when(repository.findById(41L)).thenReturn(java.util.Optional.of(running));
        when(repository.finish(
                lease, AdminJobStatus.FAILED, "EXECUTION_FAILED", "Job handler failed"))
                .thenReturn(true);

        auditedService.create(
                ADMIN, AdminJobType.DOCUMENT_REINDEX, "DOCUMENT", 7L,
                Map.of("documentId", 7L));
        auditedService.retry(ADMIN, 41L);
        assertThat(auditedService.finish(
                lease, AdminJobStatus.FAILED,
                "EXECUTION_FAILED", "Job handler failed")).isTrue();

        ArgumentCaptor<AdminAuditEvent> events = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit, Mockito.times(2)).audited(events.capture(), Mockito.any());
        verify(audit).auditedOutcome(events.capture(), Mockito.any(), Mockito.any());
        assertThat(events.getAllValues()).extracting(AdminAuditEvent::action)
                .containsExactly(
                        "DOCUMENT_REINDEX_REQUESTED", "ADMIN_JOB_RETRIED", "ADMIN_JOB_FINISHED");
        assertThat(events.getAllValues().getLast().result()).isEqualTo(AdminAuditResult.FAILED);
    }

    @Test
    void staleJobExpirationUsesOneAtomicBatchAuditBoundary() {
        AdminAuditService audit = passthroughAudit();
        AdminJobService auditedService = new AdminJobService(repository, audit);
        AdminJobDto expired = job(AdminJobStatus.FAILED, 0);
        when(repository.expireStaleJobs(Duration.ofHours(1))).thenReturn(List.of(expired));

        assertThat(auditedService.expireStale(Duration.ofHours(1))).isEqualTo(1);

        verify(audit).auditedMany(Mockito.any(), Mockito.any(), Mockito.any());
        verify(repository).expireStaleJobs(Duration.ofHours(1));
    }

    private AdminAuditService passthroughAudit() {
        AdminAuditService audit = mock(AdminAuditService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(audit).audited(Mockito.any(), Mockito.any());
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(audit).auditedOutcome(Mockito.any(), Mockito.any(), Mockito.any());
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(audit).auditedMany(Mockito.any(), Mockito.any(), Mockito.any());
        return audit;
    }

    private AdminJobDto job(AdminJobStatus status, int attempt) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-25T00:00:00Z");
        return new AdminJobDto(
                41L, AdminJobType.DOCUMENT_REINDEX, status, "DOCUMENT", 7L, 1L,
                attempt, null, null, null, null, null, null, null, null,
                Map.of(), now, now, List.of());
    }
}

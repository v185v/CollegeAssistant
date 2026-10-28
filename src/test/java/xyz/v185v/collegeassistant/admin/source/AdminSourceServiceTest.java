package xyz.v185v.collegeassistant.admin.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.SourceScheduleCalculator;

class AdminSourceServiceTest {

    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");

    @Test
    void createsValidatedRemoteSourceWithSafeDefaults() {
        SourceRepository repository = mock(SourceRepository.class);
        when(repository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AdminSourceService service = new AdminSourceService(
                repository,
                mock(IngestionService.class),
                mock(AdminJobService.class),
                mock(SourcePreviewGateway.class));

        SourceDto created = service.create(ADMIN, request(
                SourceConnectorType.SINGLE_URL, "https://catalog.example.edu/plan", SourceScheduleType.MANUAL));

        assertThat(created.connectorType()).isEqualTo(SourceConnectorType.SINGLE_URL);
        assertThat(created.baseUrl()).isEqualTo("https://catalog.example.edu/plan");
        verify(repository).create(any(SourceRecord.class));
    }

    @Test
    void sourceCreationUsesAnAtomicAuditBoundary() {
        SourceRepository repository = mock(SourceRepository.class);
        AdminAuditService auditService = mock(AdminAuditService.class);
        when(repository.create(any())).thenAnswer(invocation ->
                withId(invocation.getArgument(0), 33L));
        AtomicReference<AdminAuditEvent> successful = new AtomicReference<>();
        when(auditService.auditedResult(any(), any(), any())).thenAnswer(invocation -> {
            Object result = ((Supplier<?>) invocation.getArgument(1)).get();
            @SuppressWarnings("unchecked")
            Function<Object, AdminAuditEvent> eventFactory = invocation.getArgument(2);
            successful.set(eventFactory.apply(result));
            return result;
        });
        AdminSourceService service = new AdminSourceService(
                repository,
                mock(IngestionService.class),
                mock(AdminJobService.class),
                mock(SourcePreviewGateway.class),
                new SourceScheduleCalculator(),
                Clock.systemUTC(),
                auditService);

        service.create(ADMIN, request(
                SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu/plan",
                SourceScheduleType.MANUAL));

        ArgumentCaptor<AdminAuditEvent> requested = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).auditedResult(requested.capture(), any(), any());
        assertThat(requested.getValue().action()).isEqualTo("SOURCE_CREATED");
        assertThat(requested.getValue().actorId()).isEqualTo(ADMIN.userId());
        assertThat(successful.get().targetType()).isEqualTo("SOURCE");
        assertThat(successful.get().targetId()).isEqualTo(33L);
    }

    @Test
    void createsScheduledSourceWithItsFirstCalculatedNextRun() {
        SourceRepository repository = mock(SourceRepository.class);
        when(repository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Clock clock = Clock.fixed(Instant.parse("2026-07-25T00:30:00Z"), ZoneOffset.UTC);
        AdminSourceService service = new AdminSourceService(
                repository,
                mock(IngestionService.class),
                mock(AdminJobService.class),
                mock(SourcePreviewGateway.class),
                new SourceScheduleCalculator(),
                clock);

        SourceDto created = service.create(ADMIN, request(
                SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu/plan",
                SourceScheduleType.DAILY,
                "0 0 9 * * *"));

        assertThat(created.nextRunAt()).isEqualTo(OffsetDateTime.parse("2026-07-25T01:00:00Z"));
        assertThat(created.cronExpression()).isEqualTo("0 0 9 * * *");
    }

    @Test
    void rejectsInvalidConnectorAndScheduleCombinations() {
        AdminSourceService service = new AdminSourceService(
                mock(SourceRepository.class),
                mock(IngestionService.class),
                mock(AdminJobService.class),
                mock(SourcePreviewGateway.class));

        assertThatThrownBy(() -> service.create(ADMIN, request(
                SourceConnectorType.LOCAL_UPLOAD, "https://invalid.example.edu", SourceScheduleType.MANUAL)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.create(ADMIN, request(
                SourceConnectorType.LOCAL_UPLOAD, null, SourceScheduleType.CRON,
                "0 */15 * * * *")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.create(ADMIN, request(
                SourceConnectorType.SINGLE_URL, "https://valid.example.edu", SourceScheduleType.CRON,
                "0 */5 * * * *")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsCrawlLimitsOutsideTheAdministrationContract() {
        AdminSourceService service = new AdminSourceService(
                mock(SourceRepository.class),
                mock(IngestionService.class),
                mock(AdminJobService.class),
                mock(SourcePreviewGateway.class));

        assertValidationFailure(service, requestWithLimits(6, 500, 4));
        assertValidationFailure(service, requestWithLimits(3, 1001, 4));
        assertValidationFailure(service, requestWithLimits(3, 500, 9));
    }

    @Test
    void deletionClaimLocksSourceChecksRunsAndCreatesDurableJob() {
        SourceRepository repository = mock(SourceRepository.class);
        IngestionService ingestion = mock(IngestionService.class);
        AdminJobService jobs = mock(AdminJobService.class);
        SourceRecord source = new SourceRecord(
                10L, "Registrar", "https://catalog.example.edu", "OFFICIAL", true, NOW, NOW);
        AdminJobDto job = new AdminJobDto(
                40L, AdminJobType.SOURCE_DELETE, AdminJobStatus.QUEUED, "SOURCE", 10L, 1L,
                0, null, null, null, null, null, null, null, null, Map.of(), NOW, NOW, List.of());
        when(repository.lockForMutation(10L)).thenReturn(java.util.Optional.of(source));
        when(repository.markDeleting(10L)).thenReturn(true);
        when(jobs.create(eq(ADMIN), eq(AdminJobType.SOURCE_DELETE), eq("SOURCE"), eq(10L), any()))
                .thenReturn(job);
        AdminSourceService service = new AdminSourceService(
                repository, ingestion, jobs, mock(SourcePreviewGateway.class));

        assertThat(service.requestDeletion(ADMIN, 10L)).isEqualTo(job);

        verify(ingestion).requireNoActiveRun(10L);
        verify(repository).markDeleting(10L);
    }

    private SourceRequest request(
            SourceConnectorType connectorType,
            String baseUrl,
            SourceScheduleType scheduleType
    ) {
        return request(connectorType, baseUrl, scheduleType, null);
    }

    private SourceRequest request(
            SourceConnectorType connectorType,
            String baseUrl,
            SourceScheduleType scheduleType,
            String cronExpression
    ) {
        return new SourceRequest(
                "Registrar", "OFFICIAL", connectorType, baseUrl, true, scheduleType,
                "Asia/Shanghai", cronExpression, true, 3, 500, 4, List.of(), List.of());
    }

    private SourceRequest requestWithLimits(int maxDepth, int maxPages, int maxConcurrency) {
        return new SourceRequest(
                "Registrar", "OFFICIAL", SourceConnectorType.SITE,
                "https://catalog.example.edu", true, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, maxDepth, maxPages, maxConcurrency,
                List.of(), List.of());
    }

    private void assertValidationFailure(AdminSourceService service, SourceRequest request) {
        assertThatThrownBy(() -> service.create(ADMIN, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private SourceRecord withId(SourceRecord source, Long id) {
        return new SourceRecord(
                id, source.name(), source.baseUrl(), source.type(), source.enabled(),
                source.connectorType(), source.deletionStatus(), source.scheduleType(),
                source.scheduleZone(), source.cronExpression(), source.sameHostOnly(),
                source.maxDepth(), source.maxPages(), source.maxConcurrency(),
                source.includePatterns(), source.excludePatterns(), source.nextRunAt(),
                source.lastRunAt(), source.createdBy(), source.createdAt(), source.updatedAt());
    }
}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.IngestRunExpirationService;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditResult;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;

class IngestRunExpirationServiceTest {

    @Test
    void globalExpirationWritesOneTerminalEventPerChangedRun() {
        IngestRunRepository repository = mock(IngestRunRepository.class);
        AdminAuditService auditService = mock(AdminAuditService.class);
        AtomicReference<List<AdminAuditEvent>> events = passthroughMany(auditService);
        IngestRunDto expired = expiredRun(41L, 7L);
        when(repository.expireStaleRuns(Duration.ofHours(1))).thenReturn(List.of(expired));
        IngestRunExpirationService service =
                new IngestRunExpirationService(repository, auditService);

        assertThat(service.expireStale(Duration.ofHours(1))).isEqualTo(1);

        verify(repository).expireStaleRuns(Duration.ofHours(1));
        assertThat(events.get()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("INGESTION_FINISHED");
            assertThat(event.targetType()).isEqualTo("INGEST_RUN");
            assertThat(event.targetId()).isEqualTo(41L);
            assertThat(event.result()).isEqualTo(AdminAuditResult.FAILED);
            assertThat(event.summary())
                    .containsEntry("status", "FAILED")
                    .containsEntry("runErrorCode", "LEASE_EXPIRED")
                    .containsEntry("sourceId", 7L);
        });
    }

    @Test
    void sourceScopedExpirationUsesTheSameAtomicBatchBoundary() {
        IngestRunRepository repository = mock(IngestRunRepository.class);
        AdminAuditService auditService = mock(AdminAuditService.class);
        AtomicReference<List<AdminAuditEvent>> events = passthroughMany(auditService);
        when(repository.expireStaleRunsForSource(7L, Duration.ofHours(1)))
                .thenReturn(List.of(expiredRun(42L, 7L)));
        IngestRunExpirationService service =
                new IngestRunExpirationService(repository, auditService);

        assertThat(service.expireStaleForSource(7L, Duration.ofHours(1))).isEqualTo(1);

        verify(repository).expireStaleRunsForSource(7L, Duration.ofHours(1));
        assertThat(events.get()).singleElement()
                .extracting(AdminAuditEvent::targetId)
                .isEqualTo(42L);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<List<AdminAuditEvent>> passthroughMany(AdminAuditService auditService) {
        AtomicReference<List<AdminAuditEvent>> events = new AtomicReference<>(List.of());
        when(auditService.auditedMany(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<List<IngestRunDto>> operation = invocation.getArgument(1);
            Function<List<IngestRunDto>, List<AdminAuditEvent>> eventFactory =
                    invocation.getArgument(2);
            List<IngestRunDto> result = operation.get();
            events.set(eventFactory.apply(result));
            return result;
        });
        return events;
    }

    private IngestRunDto expiredRun(Long id, Long sourceId) {
        OffsetDateTime started = OffsetDateTime.parse("2026-07-25T00:00:00Z");
        return new IngestRunDto(
                id, sourceId, IngestRunStatus.FAILED,
                0, 0, 0, 0, "lease expired", started, started.plusHours(2));
    }
}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.DeferredIngestDispatcher;
import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.application.IngestRunExpirationService;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.document.SourceRecord;

class DeferredIngestDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:30:00Z");

    @Test
    void resumesEachAtomicallyClaimedDeferredRunOnce() {
        IngestRunRepository repository = mock(IngestRunRepository.class);
        IngestionService ingestionService = mock(IngestionService.class);
        IngestRunExpirationService expirationService = mock(IngestRunExpirationService.class);
        ScheduledIngestClaim claim = new ScheduledIngestClaim(
                new SourceRecord(10L, "Catalog", "https://catalog.example.edu", "OFFICIAL", true,
                        OffsetDateTime.now(), OffsetDateTime.now()),
                new IngestRunDto(20L, 10L, IngestRunStatus.RUNNING,
                        0, 0, 0, 0, null, OffsetDateTime.now(), null),
                new IngestRunLease(20L, 1, "deferred-test", UUID.randomUUID()),
                List.of());
        DeferredIngestDispatcher dispatcher = new DeferredIngestDispatcher(
                repository,
                ingestionService,
                expirationService,
                "deferred-test",
                8,
                Duration.ofHours(1),
                Clock.fixed(NOW, ZoneOffset.UTC));
        OffsetDateTime expectedNow = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(repository.claimDueDeferred(eq(8), eq(expectedNow), eq("deferred-test")))
                .thenReturn(List.of(claim));

        dispatcher.resumeDeferredRuns();

        verify(expirationService).expireStale(Duration.ofHours(1));
        verify(ingestionService).resume(claim);
    }

    @Test
    void doesNotResumeWhenNoDeferredRunWasClaimed() {
        IngestRunRepository repository = mock(IngestRunRepository.class);
        IngestionService ingestionService = mock(IngestionService.class);
        IngestRunExpirationService expirationService = mock(IngestRunExpirationService.class);
        DeferredIngestDispatcher dispatcher = new DeferredIngestDispatcher(
                repository,
                ingestionService,
                expirationService,
                "deferred-test",
                8,
                Duration.ofHours(1),
                Clock.fixed(NOW, ZoneOffset.UTC));
        OffsetDateTime expectedNow = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(repository.claimDueDeferred(eq(8), eq(expectedNow), eq("deferred-test")))
                .thenReturn(List.of());

        dispatcher.resumeDeferredRuns();

        verifyNoInteractions(ingestionService);
    }
}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.IngestionRunCoordinator;
import xyz.v185v.collegeassistant.ingestion.application.IngestRunExpirationService;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;

class IngestionRunCoordinatorTest {

    @Test
    void manualClaimAuditsSourceScopedExpirationBeforeStartingReplacement() {
        Fixture fixture = fixture(SourceConnectorType.SINGLE_URL);

        var claimed = fixture.coordinator().claimManual(7L, "manual", "worker-a");

        assertThat(claimed.lease()).isEqualTo(fixture.lease());
        InOrder order = inOrder(fixture.expirationService(), fixture.runRepository());
        order.verify(fixture.expirationService())
                .expireStaleForSource(7L, Duration.ofHours(1));
        order.verify(fixture.runRepository())
                .startLease(7L, "manual", "MANUAL", "worker-a");
    }

    @Test
    void importClaimAuditsSourceScopedExpirationBeforeStartingReplacement() {
        Fixture fixture = fixture(SourceConnectorType.LOCAL_UPLOAD);
        when(fixture.itemRepository().upsertItem(fixture.lease(), "client-a", "FILE"))
                .thenReturn(Optional.of(mock(IngestRunItemDto.class)));

        var claimed = fixture.coordinator().claimImport(
                7L, "upload", List.of("client-a"), "worker-a");

        assertThat(claimed.lease()).isEqualTo(fixture.lease());
        InOrder order = inOrder(fixture.expirationService(), fixture.runRepository());
        order.verify(fixture.expirationService())
                .expireStaleForSource(7L, Duration.ofHours(1));
        order.verify(fixture.runRepository())
                .startLease(7L, "upload", "IMPORT", "worker-a");
    }

    private Fixture fixture(SourceConnectorType connectorType) {
        SourceRepository sourceRepository = mock(SourceRepository.class);
        IngestRunRepository runRepository = mock(IngestRunRepository.class);
        IngestRunItemRepository itemRepository = mock(IngestRunItemRepository.class);
        IngestRunExpirationService expirationService = mock(IngestRunExpirationService.class);
        IngestionRunCoordinator coordinator = new IngestionRunCoordinator(
                sourceRepository, runRepository, itemRepository, expirationService);
        SourceRecord source = new SourceRecord(
                7L, "Source", connectorType == SourceConnectorType.LOCAL_UPLOAD
                        ? null : "https://example.edu",
                "OFFICIAL", true, connectorType, "NONE", SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 3, 500, 4, List.of(), List.of(),
                null, null, 1L, OffsetDateTime.now(), OffsetDateTime.now());
        IngestRunLease lease = new IngestRunLease(41L, 0, "worker-a", UUID.randomUUID());
        IngestRunDto running = new IngestRunDto(
                41L, 7L, IngestRunStatus.RUNNING,
                0, 0, 0, 0, null, OffsetDateTime.now(), null);
        when(sourceRepository.lockForMutation(7L)).thenReturn(Optional.of(source));
        when(runRepository.startLease(7L, "manual", "MANUAL", "worker-a"))
                .thenReturn(lease);
        when(runRepository.startLease(7L, "upload", "IMPORT", "worker-a"))
                .thenReturn(lease);
        when(runRepository.findById(41L)).thenReturn(Optional.of(running));
        return new Fixture(
                coordinator, runRepository, itemRepository, expirationService, lease);
    }

    private record Fixture(
            IngestionRunCoordinator coordinator,
            IngestRunRepository runRepository,
            IngestRunItemRepository itemRepository,
            IngestRunExpirationService expirationService,
            IngestRunLease lease
    ) {
    }
}

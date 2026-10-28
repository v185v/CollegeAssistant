package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class IngestRunLeaseHeartbeatSchedulerTest {

    @Test
    void heartbeatsRegisteredLeaseOutsideTheWorkerExecutor() {
        IngestRunRepository repository = mock(IngestRunRepository.class);
        IngestRunLease lease = new IngestRunLease(
                77L, 2, "worker-a",
                UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        when(repository.heartbeat(lease)).thenReturn(true);
        IngestRunLeaseHeartbeatScheduler scheduler =
                new IngestRunLeaseHeartbeatScheduler(repository);

        scheduler.register(lease);
        scheduler.heartbeatActiveLeases();

        verify(repository).heartbeat(lease);
        assertThat(scheduler.activeLeaseCount()).isEqualTo(1);
    }

    @Test
    void lostLeaseIsRemovedFromTheHeartbeatSet() {
        IngestRunRepository repository = mock(IngestRunRepository.class);
        IngestRunLease lease = new IngestRunLease(
                77L, 2, "worker-a",
                UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        when(repository.heartbeat(lease)).thenReturn(false);
        IngestRunLeaseHeartbeatScheduler scheduler =
                new IngestRunLeaseHeartbeatScheduler(repository);

        scheduler.register(lease);
        scheduler.heartbeatActiveLeases();

        assertThat(scheduler.activeLeaseCount()).isZero();
    }
}

package xyz.v185v.collegeassistant.admin.job;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdminJobSchedulerTest {

    @Test
    void expiresThenClaimsAndSubmitsOnlyCommittedQueueRows() {
        AdminJobRepository repository = mock(AdminJobRepository.class);
        AdminJobDispatcher dispatcher = mock(AdminJobDispatcher.class);
        AdminJobService jobService = mock(AdminJobService.class);
        AdminJobLease first = lease(1L);
        AdminJobLease second = lease(2L);
        when(repository.claimNext("worker-a"))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());
        AdminJobScheduler scheduler = new AdminJobScheduler(
                repository, dispatcher, jobService, "worker-a", 8, Duration.ofHours(1));

        scheduler.dispatchQueuedJobs();

        verify(jobService).expireStale(Duration.ofHours(1));
        verify(repository, never()).expireStale(Duration.ofHours(1));
        verify(dispatcher).submit(first);
        verify(dispatcher).submit(second);
        verify(repository, org.mockito.Mockito.times(3)).claimNext("worker-a");
    }

    @Test
    void heartbeatRunsOutsideClaimLoop() {
        AdminJobDispatcher dispatcher = mock(AdminJobDispatcher.class);
        AdminJobScheduler scheduler = new AdminJobScheduler(
                mock(AdminJobRepository.class), dispatcher,
                "worker-a", 1, Duration.ofHours(1));

        scheduler.heartbeatActiveJobs();

        verify(dispatcher).heartbeatActiveLeases();
    }

    private AdminJobLease lease(Long id) {
        return new AdminJobLease(id, 0, "worker-a", UUID.randomUUID());
    }
}

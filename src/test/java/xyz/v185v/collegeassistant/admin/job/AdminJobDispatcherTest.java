package xyz.v185v.collegeassistant.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

class AdminJobDispatcherTest {

    @Test
    void successfulHandlerFinishesTheClaimedLease() {
        AdminJobRepository repository = mock(AdminJobRepository.class);
        AdminJobLeaseGuard guard = mock(AdminJobLeaseGuard.class);
        AdminJobHandler handler = mock(AdminJobHandler.class);
        AdminJobLease lease = lease();
        when(handler.type()).thenReturn(AdminJobType.CONSISTENCY_SCAN);
        when(repository.findById(lease.jobId())).thenReturn(Optional.of(job()));
        AdminJobDispatcher dispatcher = new AdminJobDispatcher(
                repository,
                new AdminJobHandlerRegistry(List.of(handler)),
                guard,
                Runnable::run);

        assertThat(dispatcher.submit(lease)).isTrue();

        verify(handler).execute(any(AdminJobExecutionContext.class));
        verify(repository).finish(lease, AdminJobStatus.SUCCEEDED, null, null);
    }

    @Test
    void terminalTransitionsAreRoutedThroughTheAuditedJobService() {
        AdminJobRepository repository = mock(AdminJobRepository.class);
        AdminJobService jobService = mock(AdminJobService.class);
        AdminJobHandler handler = mock(AdminJobHandler.class);
        AdminJobLease lease = lease();
        when(handler.type()).thenReturn(AdminJobType.CONSISTENCY_SCAN);
        when(repository.findById(lease.jobId())).thenReturn(Optional.of(job()));
        AdminJobDispatcher dispatcher = new AdminJobDispatcher(
                repository,
                new AdminJobHandlerRegistry(List.of(handler)),
                mock(AdminJobLeaseGuard.class),
                jobService,
                Runnable::run);

        assertThat(dispatcher.submit(lease)).isTrue();

        verify(jobService).finish(lease, AdminJobStatus.SUCCEEDED, null, null);
        verify(repository, never()).finish(any(), any(), any(), any());
    }

    @Test
    void missingHandlerFailsWithSanitizedCode() {
        AdminJobRepository repository = mock(AdminJobRepository.class);
        AdminJobLease lease = lease();
        when(repository.findById(lease.jobId())).thenReturn(Optional.of(job()));
        AdminJobDispatcher dispatcher = new AdminJobDispatcher(
                repository,
                new AdminJobHandlerRegistry(List.of()),
                mock(AdminJobLeaseGuard.class),
                Runnable::run);

        assertThat(dispatcher.submit(lease)).isTrue();

        verify(repository).finish(
                lease, AdminJobStatus.FAILED, "HANDLER_MISSING", "Job handler is unavailable");
    }

    @Test
    void partialSuccessHandlerFinishesWithItsSafeOutcome() {
        AdminJobRepository repository = mock(AdminJobRepository.class);
        AdminJobLeaseGuard guard = mock(AdminJobLeaseGuard.class);
        AdminJobHandler handler = mock(AdminJobHandler.class);
        AdminJobLease lease = lease();
        when(handler.type()).thenReturn(AdminJobType.CONSISTENCY_SCAN);
        when(repository.findById(lease.jobId())).thenReturn(Optional.of(job()));
        doThrow(new AdminJobPartialSuccessException(
                "EMBEDDING_REBUILD_INCOMPLETE", "Some targets could not be rebuilt"))
                .when(handler).execute(any(AdminJobExecutionContext.class));
        AdminJobDispatcher dispatcher = new AdminJobDispatcher(
                repository,
                new AdminJobHandlerRegistry(List.of(handler)),
                guard,
                Runnable::run);

        assertThat(dispatcher.submit(lease)).isTrue();

        verify(repository).finish(
                lease,
                AdminJobStatus.PARTIAL_SUCCESS,
                "EMBEDDING_REBUILD_INCOMPLETE",
                "Some targets could not be rebuilt");
    }

    @Test
    void unexpectedHandlerFailureStillUsesTheGenericSanitizedOutcome() {
        AdminJobRepository repository = mock(AdminJobRepository.class);
        AdminJobHandler handler = mock(AdminJobHandler.class);
        AdminJobLease lease = lease();
        when(handler.type()).thenReturn(AdminJobType.CONSISTENCY_SCAN);
        when(repository.findById(lease.jobId())).thenReturn(Optional.of(job()));
        doThrow(new IllegalStateException("sensitive worker details"))
                .when(handler).execute(any(AdminJobExecutionContext.class));
        AdminJobDispatcher dispatcher = new AdminJobDispatcher(
                repository,
                new AdminJobHandlerRegistry(List.of(handler)),
                mock(AdminJobLeaseGuard.class),
                Runnable::run);

        assertThat(dispatcher.submit(lease)).isTrue();

        verify(repository).finish(
                lease, AdminJobStatus.FAILED, "EXECUTION_FAILED", "Job handler failed");
    }

    @Test
    void rejectedSubmissionFailsTheDurableJobWithoutLeakingExecutorDetails() {
        AdminJobRepository repository = mock(AdminJobRepository.class);
        Executor rejecting = command -> {
            throw new RejectedExecutionException("secret queue internals");
        };
        AdminJobLease lease = lease();
        AdminJobDispatcher dispatcher = new AdminJobDispatcher(
                repository,
                new AdminJobHandlerRegistry(List.of()),
                mock(AdminJobLeaseGuard.class),
                rejecting);

        assertThat(dispatcher.submit(lease)).isFalse();

        verify(repository).finish(
                eq(lease), eq(AdminJobStatus.FAILED), eq("SUBMISSION_REJECTED"),
                eq("Job could not be scheduled"));
        assertThat(dispatcher.activeLeaseCount()).isZero();
    }

    private AdminJobLease lease() {
        return new AdminJobLease(91L, 0, "worker-a", UUID.fromString(
                "12345678-1234-1234-1234-123456789abc"));
    }

    private AdminJobDto job() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-25T00:00:00Z");
        return new AdminJobDto(
                91L, AdminJobType.CONSISTENCY_SCAN, AdminJobStatus.RUNNING,
                "DATABASE", null, 1L, 0, "worker-a",
                UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                now, now, now, null, null, null, Map.of(), now, now, List.of());
    }
}

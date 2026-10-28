package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

class UgcIndexDispatcherTest {

    @Test
    void acceptedDispatchReturnsBeforeIndexingAndQueuedTaskRunsTheService() {
        QueueingExecutor executor = new QueueingExecutor();
        UgcIndexingService indexingService = mock(UgcIndexingService.class);
        UgcIndexDispatcher dispatcher = new UgcIndexDispatcher(indexingService, executor);

        boolean accepted = dispatcher.dispatch(41L);

        assertThat(accepted).isTrue();
        verifyNoInteractions(indexingService);

        executor.runNext();
        verify(indexingService).indexApproved(41L);
    }

    @Test
    void queueRejectionIsLoggedAndReturnsFalseWithoutEscaping() {
        UgcIndexingService indexingService = mock(UgcIndexingService.class);
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full with sensitive task details");
        };
        UgcIndexDispatcher dispatcher = new UgcIndexDispatcher(indexingService, rejecting);

        assertThatCode(() -> assertThat(dispatcher.dispatch(41L)).isFalse())
                .doesNotThrowAnyException();

        verifyNoInteractions(indexingService);
    }

    @Test
    void backgroundIndexFailureIsIsolatedFromTheExecutorThread() {
        UgcIndexingService indexingService = mock(UgcIndexingService.class);
        UgcIndexingException failure = new UgcIndexingException(
                41L,
                UgcIndexingStage.EMBEDDING,
                new IllegalStateException("provider response contained sensitive details"));
        org.mockito.Mockito.doThrow(failure).when(indexingService).indexApproved(41L);
        UgcIndexDispatcher dispatcher = new UgcIndexDispatcher(indexingService, Runnable::run);

        assertThatCode(() -> assertThat(dispatcher.dispatch(41L)).isTrue())
                .doesNotThrowAnyException();

        verify(indexingService).indexApproved(41L);
    }

    private static final class QueueingExecutor implements Executor {

        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }
    }
}

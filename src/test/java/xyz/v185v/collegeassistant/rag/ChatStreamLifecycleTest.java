package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class ChatStreamLifecycleTest {

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void cancelIsIdempotentAndEveryCompensationIsBestEffort(
            CapturedOutput output
    ) {
        AtomicInteger cleanupCalls = new AtomicInteger();
        ChatStreamLifecycle lifecycle = new ChatStreamLifecycle(() -> {
            cleanupCalls.incrementAndGet();
            throw new RuntimeException("cleanup-secret");
        });
        RecordingFuture worker = new RecordingFuture("worker-secret");
        RecordingProvider provider = new RecordingProvider("provider-secret");
        lifecycle.attachWorker(worker);
        lifecycle.attachProvider(provider);

        Throwable escaped = catchThrowable(() -> {
            lifecycle.cancel();
            lifecycle.cancel();
        });

        assertThat(escaped).isNull();
        assertThat(lifecycle.isCancelled()).isTrue();
        assertThat(lifecycle.isActive()).isFalse();
        assertThat(worker.cancelCalls).isEqualTo(1);
        assertThat(provider.cancelCalls).isEqualTo(1);
        assertThat(cleanupCalls).hasValue(1);
        assertThat(output.getAll())
                .contains(
                        "action=worker.cancel causeType=java.lang.RuntimeException",
                        "action=provider.cancel causeType=java.lang.RuntimeException",
                        "action=assistant.discard causeType=java.lang.RuntimeException")
                .doesNotContain(
                        "worker-secret",
                        "provider-secret",
                        "cleanup-secret",
                        "java.lang.RuntimeException:",
                        "\tat ");
    }

    @Test
    void lateWorkerAndProviderAreSafelyCancelledAfterEveryTerminalPath() {
        ChatStreamLifecycle cancelled = new ChatStreamLifecycle(() -> { });
        cancelled.cancel();
        assertLateAttachmentsAreSafelyCancelled(cancelled);

        ChatStreamLifecycle failed = new ChatStreamLifecycle(() -> { });
        assertThat(failed.fail()).isTrue();
        assertLateAttachmentsAreSafelyCancelled(failed);

        ChatStreamLifecycle completed = new ChatStreamLifecycle(() -> { });
        assertThat(completed.beginCompletion()).isTrue();
        assertLateAttachmentsAreSafelyCancelled(completed);
    }

    @Test
    void completionFailureRunsCompensationOnlyOnce() {
        AtomicInteger cleanupCalls = new AtomicInteger();
        ChatStreamLifecycle lifecycle =
                new ChatStreamLifecycle(cleanupCalls::incrementAndGet);
        RecordingFuture worker = new RecordingFuture(null);
        RecordingProvider provider = new RecordingProvider(null);
        lifecycle.attachWorker(worker);
        lifecycle.attachProvider(provider);

        assertThat(lifecycle.beginCompletion()).isTrue();
        assertThat(lifecycle.beginCompletion()).isFalse();
        assertThat(lifecycle.fail()).isFalse();
        assertThat(lifecycle.failAfterCompletion()).isTrue();
        assertThat(lifecycle.failAfterCompletion()).isFalse();

        assertThat(lifecycle.isCancelled()).isFalse();
        assertThat(lifecycle.isActive()).isFalse();
        assertThat(worker.cancelCalls).isEqualTo(1);
        assertThat(provider.cancelCalls).isEqualTo(1);
        assertThat(cleanupCalls).hasValue(1);
    }

    @Test
    void activeEmissionAndConcurrentCancellationAreAtomic()
            throws Exception {
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicInteger emissions = new AtomicInteger();
        AtomicBoolean emitted = new AtomicBoolean();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        CountDownLatch emissionEntered = new CountDownLatch(1);
        CountDownLatch releaseEmission = new CountDownLatch(1);
        CountDownLatch cancelStarted = new CountDownLatch(1);
        CountDownLatch cancelReturned = new CountDownLatch(1);
        ChatStreamLifecycle lifecycle =
                new ChatStreamLifecycle(cleanupCalls::incrementAndGet);
        Thread emissionThread = new Thread(() -> {
            try {
                emitted.set(lifecycle.emitIfActive(() -> {
                    emissionEntered.countDown();
                    try {
                        if (!releaseEmission.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("emission release timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    emissions.incrementAndGet();
                }));
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            }
        });
        Thread cancelThread = new Thread(() -> {
            cancelStarted.countDown();
            try {
                lifecycle.cancel();
            } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
            } finally {
                cancelReturned.countDown();
            }
        });
        emissionThread.setDaemon(true);
        cancelThread.setDaemon(true);

        try {
            emissionThread.start();
            assertThat(emissionEntered.await(2, TimeUnit.SECONDS)).isTrue();
            cancelThread.start();
            assertThat(cancelStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelReturned.await(100, TimeUnit.MILLISECONDS))
                    .isFalse();
            assertThat(cleanupCalls).hasValue(0);
        } finally {
            releaseEmission.countDown();
        }
        emissionThread.join(2_000);
        cancelThread.join(2_000);

        assertThat(threadFailure.get()).isNull();
        assertThat(emissionThread.isAlive()).isFalse();
        assertThat(cancelThread.isAlive()).isFalse();
        assertThat(emitted).isTrue();
        assertThat(emissions).hasValue(1);
        assertThat(cleanupCalls).hasValue(1);
        assertThat(lifecycle.emitIfActive(emissions::incrementAndGet))
                .isFalse();
        assertThat(emissions).hasValue(1);
    }

    @Test
    void reentrantCancellationDefersCompensationUntilEmissionReturns() {
        AtomicInteger cleanupCalls = new AtomicInteger();
        ChatStreamLifecycle lifecycle =
                new ChatStreamLifecycle(cleanupCalls::incrementAndGet);
        RecordingFuture worker = new RecordingFuture(null);
        RecordingProvider provider = new RecordingProvider(null);
        lifecycle.attachWorker(worker);
        lifecycle.attachProvider(provider);

        boolean emitted = lifecycle.emitIfActive(() -> {
            lifecycle.cancel();

            assertThat(lifecycle.isCancelled()).isTrue();
            assertThat(lifecycle.isActive()).isFalse();
            assertThat(worker.cancelCalls).isZero();
            assertThat(provider.cancelCalls).isZero();
            assertThat(cleanupCalls).hasValue(0);
        });

        assertThat(emitted).isTrue();
        assertThat(worker.cancelCalls).isEqualTo(1);
        assertThat(provider.cancelCalls).isEqualTo(1);
        assertThat(cleanupCalls).hasValue(1);
    }

    @Test
    void exceptionalEmissionStillDrainsDeferredCancellation() {
        AtomicInteger cleanupCalls = new AtomicInteger();
        ChatStreamLifecycle lifecycle =
                new ChatStreamLifecycle(cleanupCalls::incrementAndGet);
        RecordingFuture worker = new RecordingFuture(null);
        RecordingProvider provider = new RecordingProvider(null);
        lifecycle.attachWorker(worker);
        lifecycle.attachProvider(provider);
        RuntimeException failure = new RuntimeException("emission failed");

        Throwable escaped = catchThrowable(() -> lifecycle.emitIfActive(() -> {
            lifecycle.cancel();

            assertThat(worker.cancelCalls).isZero();
            assertThat(provider.cancelCalls).isZero();
            assertThat(cleanupCalls).hasValue(0);
            throw failure;
        }));

        assertThat(escaped).isSameAs(failure);
        assertThat(worker.cancelCalls).isEqualTo(1);
        assertThat(provider.cancelCalls).isEqualTo(1);
        assertThat(cleanupCalls).hasValue(1);
    }

    private void assertLateAttachmentsAreSafelyCancelled(
            ChatStreamLifecycle lifecycle
    ) {
        RecordingFuture worker = new RecordingFuture("late-worker-secret");
        RecordingProvider provider =
                new RecordingProvider("late-provider-secret");

        Throwable escaped = catchThrowable(() -> {
            lifecycle.attachWorker(worker);
            lifecycle.attachProvider(provider);
        });

        assertThat(escaped).isNull();
        assertThat(worker.cancelCalls).isEqualTo(1);
        assertThat(provider.cancelCalls).isEqualTo(1);
    }

    private static final class RecordingFuture extends FutureTask<Void> {

        private final String failureMessage;
        private int cancelCalls;

        private RecordingFuture(String failureMessage) {
            super(() -> null);
            this.failureMessage = failureMessage;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls++;
            if (failureMessage != null) {
                throw new RuntimeException(failureMessage);
            }
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class RecordingProvider implements StreamControl {

        private final String failureMessage;
        private int cancelCalls;

        private RecordingProvider(String failureMessage) {
            this.failureMessage = failureMessage;
        }

        @Override
        public void cancel() {
            cancelCalls++;
            if (failureMessage != null) {
                throw new RuntimeException(failureMessage);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls > 0;
        }
    }
}

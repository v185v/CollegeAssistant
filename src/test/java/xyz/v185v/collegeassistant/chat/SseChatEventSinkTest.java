package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import xyz.v185v.collegeassistant.rag.ChatStreamControl;

class SseChatEventSinkTest {

    @Test
    void timeoutCompletesEmitterAndLifecycleCallbacksCancelOnlyOnce() {
        ControlledSseEmitter emitter = new ControlledSseEmitter();
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();
        bridge.attach(control);

        new SseChatEventSink(emitter, bridge);

        assertThat(emitter.timeoutCallback).isNotNull();
        assertThat(emitter.errorCallback).isNotNull();
        assertThat(emitter.completionCallback).isNotNull();

        emitter.fireTimeout();
        emitter.fireTimeout();
        emitter.fireCompletion();
        emitter.fireError(new IOException("disconnected"));

        assertThat(emitter.completeCalls).isEqualTo(1);
        assertThat(control.cancelCalls).isEqualTo(1);
    }

    @Test
    void normalCompletionIsIdempotentAndSuppressesLateEvents() {
        ControlledSseEmitter emitter = new ControlledSseEmitter();
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();
        bridge.attach(control);
        SseChatEventSink sink = new SseChatEventSink(emitter, bridge);

        sink.complete();
        sink.complete();
        emitter.fireCompletion();
        sink.delta(new ChatDeltaDto("late"));

        assertThat(emitter.completeCalls).isEqualTo(1);
        assertThat(emitter.sendCalls).isZero();
        assertThat(control.cancelCalls).isEqualTo(1);
    }

    @Test
    void illegalStateSendFailureCompletesWithErrorAndBecomesTerminal() {
        ControlledSseEmitter emitter = new ControlledSseEmitter();
        String sensitivePayload = "private-delta";
        IllegalStateException failure = new IllegalStateException(
                "Failed to send " + sensitivePayload);
        emitter.sendRuntimeFailure = failure;
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();
        bridge.attach(control);
        SseChatEventSink sink = new SseChatEventSink(emitter, bridge);

        Throwable thrown = catchThrowable(() ->
                sink.delta(new ChatDeltaDto("first")));
        Throwable lateThrown = catchThrowable(() ->
                sink.delta(new ChatDeltaDto("late")));

        assertThat(thrown)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("SSE transport failed")
                .hasMessageNotContaining(sensitivePayload)
                .hasCause(failure);
        assertThat(emitter.completeWithErrorCalls).isEqualTo(1);
        assertThat(emitter.completionFailure).isSameAs(thrown);
        assertThat(lateThrown).isNull();
        assertThat(emitter.sendCalls).isEqualTo(1);
        assertThat(control.cancelCalls).isEqualTo(1);
    }

    @Test
    void ioSendFailureReliesOnContainerErrorAndBecomesTerminal() {
        ControlledSseEmitter emitter = new ControlledSseEmitter();
        IOException failure = new IOException("disconnected");
        emitter.sendIoFailure = failure;
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();
        bridge.attach(control);
        SseChatEventSink sink = new SseChatEventSink(emitter, bridge);

        Throwable thrown = catchThrowable(() ->
                sink.delta(new ChatDeltaDto("first")));
        Throwable lateThrown = catchThrowable(() ->
                sink.delta(new ChatDeltaDto("late")));

        assertThat(thrown)
                .isInstanceOf(RuntimeException.class)
                .hasCause(failure);
        assertThat(emitter.completeWithErrorCalls).isZero();
        assertThat(lateThrown).isNull();
        assertThat(emitter.sendCalls).isEqualTo(1);
        assertThat(control.cancelCalls).isEqualTo(1);
    }

    @Test
    void normalCompletionStillCancelsWhenEmitterCompletionFails() {
        ControlledSseEmitter emitter = new ControlledSseEmitter();
        IllegalStateException failure = new IllegalStateException("complete failed");
        emitter.completeRuntimeFailure = failure;
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();
        bridge.attach(control);
        SseChatEventSink sink = new SseChatEventSink(emitter, bridge);

        Throwable thrown = catchThrowable(sink::complete);

        assertThat(thrown).isSameAs(failure);
        assertThat(emitter.completeCalls).isEqualTo(1);
        assertThat(control.cancelCalls).isEqualTo(1);
    }

    @Test
    void timeoutStillCompletesEmitterWhenCancellationFails() {
        ControlledSseEmitter emitter = new ControlledSseEmitter();
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        IllegalStateException failure = new IllegalStateException("cancel failed");
        ThrowingControl control = new ThrowingControl(failure);
        bridge.attach(control);
        new SseChatEventSink(emitter, bridge);

        Throwable thrown = catchThrowable(emitter::fireTimeout);

        assertThat(thrown).isSameAs(failure);
        assertThat(control.cancelCalls).isEqualTo(1);
        assertThat(emitter.completeCalls).isEqualTo(1);
    }

    @Test
    void timeoutWaitsForInFlightSendBeforeCompletingEmitter()
            throws InterruptedException {
        ControlledSseEmitter emitter = new ControlledSseEmitter();
        emitter.blockSend();
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();
        bridge.attach(control);
        SseChatEventSink sink = new SseChatEventSink(emitter, bridge);
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        AtomicReference<Throwable> timeoutFailure = new AtomicReference<>();
        CountDownLatch timeoutStarted = new CountDownLatch(1);
        CountDownLatch timeoutFinished = new CountDownLatch(1);

        Thread sendThread = Thread.ofPlatform().start(() -> {
            try {
                sink.delta(new ChatDeltaDto("active"));
            } catch (Throwable failure) {
                sendFailure.set(failure);
            }
        });
        assertThat(emitter.sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread timeoutThread = Thread.ofPlatform().start(() -> {
            timeoutStarted.countDown();
            try {
                emitter.fireTimeout();
            } catch (Throwable failure) {
                timeoutFailure.set(failure);
            } finally {
                timeoutFinished.countDown();
            }
        });

        boolean timeoutFinishedWhileSendBlocked;
        try {
            assertThat(timeoutStarted.await(2, TimeUnit.SECONDS)).isTrue();
            timeoutFinishedWhileSendBlocked =
                    timeoutFinished.await(200, TimeUnit.MILLISECONDS);
        } finally {
            emitter.releaseSend();
            sendThread.join(2_000);
            timeoutThread.join(2_000);
        }

        assertThat(timeoutFinishedWhileSendBlocked).isFalse();
        assertThat(sendFailure.get()).isNull();
        assertThat(timeoutFailure.get()).isNull();
        assertThat(emitter.sendCalls).isEqualTo(1);
        assertThat(emitter.completeCalls).isEqualTo(1);
        assertThat(control.cancelCalls).isEqualTo(1);
    }

    private static final class ControlledSseEmitter extends SseEmitter {

        private Runnable timeoutCallback;
        private Consumer<Throwable> errorCallback;
        private Runnable completionCallback;
        private int completeCalls;
        private int sendCalls;
        private int completeWithErrorCalls;
        private RuntimeException sendRuntimeFailure;
        private RuntimeException completeRuntimeFailure;
        private IOException sendIoFailure;
        private Throwable completionFailure;
        private CountDownLatch sendEntered;
        private CountDownLatch releaseSend;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendCalls++;
            if (sendEntered != null) {
                sendEntered.countDown();
                try {
                    releaseSend.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
            }
            if (sendRuntimeFailure != null) {
                throw sendRuntimeFailure;
            }
            if (sendIoFailure != null) {
                throw sendIoFailure;
            }
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeoutCallback = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorCallback = callback;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallback = callback;
        }

        @Override
        public void complete() {
            completeCalls++;
            if (completeRuntimeFailure != null) {
                throw completeRuntimeFailure;
            }
        }

        @Override
        public void completeWithError(Throwable failure) {
            completeWithErrorCalls++;
            completionFailure = failure;
        }

        private void fireTimeout() {
            timeoutCallback.run();
        }

        private void fireError(Throwable error) {
            errorCallback.accept(error);
        }

        private void fireCompletion() {
            completionCallback.run();
        }

        private void blockSend() {
            sendEntered = new CountDownLatch(1);
            releaseSend = new CountDownLatch(1);
        }

        private void releaseSend() {
            releaseSend.countDown();
        }
    }

    private static final class CountingControl implements ChatStreamControl {

        private int cancelCalls;

        @Override
        public void cancel() {
            cancelCalls++;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls > 0;
        }
    }

    private static final class ThrowingControl implements ChatStreamControl {

        private final RuntimeException failure;
        private int cancelCalls;

        private ThrowingControl(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void cancel() {
            cancelCalls++;
            throw failure;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls > 0;
        }
    }
}

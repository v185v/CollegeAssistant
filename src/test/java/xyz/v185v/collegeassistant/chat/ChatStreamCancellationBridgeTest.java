package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.rag.ChatStreamControl;

class ChatStreamCancellationBridgeTest {

    @Test
    void duplicateRequestsAfterAttachCancelOnlyOnce() {
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();
        bridge.attach(control);

        bridge.requestCancellation();
        bridge.requestCancellation();

        assertThat(control.cancelCalls).isEqualTo(1);
    }

    @Test
    void pendingRequestsAreDeliveredOnceAfterAttach() {
        ChatStreamCancellationBridge bridge =
                new ChatStreamCancellationBridge();
        CountingControl control = new CountingControl();

        bridge.requestCancellation();
        bridge.requestCancellation();
        bridge.attach(control);
        bridge.requestCancellation();

        assertThat(control.cancelCalls).isEqualTo(1);
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
}

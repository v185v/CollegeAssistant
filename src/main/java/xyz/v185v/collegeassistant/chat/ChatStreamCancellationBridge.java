package xyz.v185v.collegeassistant.chat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import xyz.v185v.collegeassistant.rag.ChatStreamControl;

/**
 * 连接 SSE 回调与稍后才返回的 RAG 控制柄，确保提前发生的取消不会丢失。
 */
final class ChatStreamCancellationBridge {

    private final AtomicReference<ChatStreamControl> controlRef =
            new AtomicReference<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicBoolean cancellationDelivered = new AtomicBoolean();

    /** 记录取消请求，并在控制柄可用时至多投递一次。 */
    void requestCancellation() {
        cancellationRequested.set(true);
        deliverIfPossible();
    }

    /** 关联 RAG 控制柄，并补发可能已发生的取消请求。 */
    void attach(ChatStreamControl control) {
        controlRef.set(control);
        deliverIfPossible();
    }

    private void deliverIfPossible() {
        ChatStreamControl control = controlRef.get();
        if (cancellationRequested.get()
                && control != null
                && cancellationDelivered.compareAndSet(false, true)) {
            control.cancel();
        }
    }
}

package xyz.v185v.collegeassistant.chat;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.rag.ChatEventSink;

/**
 * 将结构化聊天事件写入 Spring MVC {@link SseEmitter} 的传输适配器。
 *
 * <p>超时、客户端断开、正常完成和发送失败都会进入唯一终态并向 RAG 层传播取消。</p>
 */
final class SseChatEventSink implements ChatEventSink {

    private final SseEmitter emitter;
    private final ChatStreamCancellationBridge cancellationBridge;
    private final Object lifecycleLock = new Object();
    private boolean terminal;

    SseChatEventSink(
            SseEmitter emitter,
            ChatStreamCancellationBridge cancellationBridge
    ) {
        this.emitter = emitter;
        this.cancellationBridge = cancellationBridge;
        emitter.onTimeout(this::handleTimeout);
        emitter.onError(error -> handleError());
        emitter.onCompletion(this::handleCompletion);
    }

    @Override
    public void meta(ChatStreamMetaDto event) {
        send("meta", event);
    }

    @Override
    public void delta(ChatDeltaDto event) {
        send("delta", event);
    }

    @Override
    public void source(SourceCitationDto event) {
        send("source", event);
    }

    @Override
    public void done(ChatDoneDto event) {
        send("done", event);
    }

    @Override
    public void error(ApiResponse<Void> event) {
        send("error", event);
    }

    @Override
    public void complete() {
        if (!markTerminal()) {
            return;
        }
        try {
            emitter.complete();
        } finally {
            cancellationBridge.requestCancellation();
        }
    }

    private void handleTimeout() {
        if (!markTerminal()) {
            cancellationBridge.requestCancellation();
            return;
        }
        try {
            cancellationBridge.requestCancellation();
        } finally {
            emitter.complete();
        }
    }

    private void handleError() {
        markTerminal();
        cancellationBridge.requestCancellation();
    }

    private void handleCompletion() {
        markTerminal();
        cancellationBridge.requestCancellation();
    }

    private void send(String name, Object data) {
        Exception failure;
        boolean newlyTerminal;
        synchronized (lifecycleLock) {
            if (terminal) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(name)
                        .data(data, MediaType.APPLICATION_JSON));
                return;
            } catch (IOException | IllegalStateException exception) {
                newlyTerminal = !terminal;
                terminal = true;
                failure = exception;
            }
        }

        StreamTransportFailure transportFailure =
                new StreamTransportFailure(failure);
        if (failure instanceof IOException) {
            if (newlyTerminal) {
                cancellationBridge.requestCancellation();
            }
            // ResponseBodyEmitter 会把 I/O 发送失败交给 Servlet 容器处理，此处不再调用 completeWithError。
            throw transportFailure;
        }
        if (newlyTerminal) {
            try {
                cancellationBridge.requestCancellation();
            } finally {
                emitter.completeWithError(transportFailure);
            }
        }
        throw transportFailure;
    }

    private boolean markTerminal() {
        synchronized (lifecycleLock) {
            if (terminal) {
                return false;
            }
            terminal = true;
            return true;
        }
    }

    private static final class StreamTransportFailure extends RuntimeException {

        private StreamTransportFailure(Throwable cause) {
            super("SSE transport failed", cause);
        }
    }
}

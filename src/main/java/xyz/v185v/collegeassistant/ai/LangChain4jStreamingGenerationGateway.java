package xyz.v185v.collegeassistant.ai;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import xyz.v185v.collegeassistant.rag.GenerationPrompt;
import xyz.v185v.collegeassistant.rag.StreamControl;
import xyz.v185v.collegeassistant.rag.StreamObserver;
import xyz.v185v.collegeassistant.rag.StreamingGenerationGateway;

/**
 * 基于 LangChain4j 的流式文本生成网关。
 *
 * <p>该适配器统一终态回调、统计日志和取消传播，并处理取消操作从模型回调中
 * 重入时可能出现的锁与提供方句柄竞争。</p>
 */
public final class LangChain4jStreamingGenerationGateway
        implements StreamingGenerationGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LangChain4jStreamingGenerationGateway.class);
    private static final int MAX_OUTPUT_TOKENS = 4_096;

    private final StreamingChatModel model;

    public LangChain4jStreamingGenerationGateway(StreamingChatModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * 启动一次流式生成，并把增量、完成或失败事件转发给观察者。
     *
     * @param prompt   分离系统策略与用户数据的生成提示
     * @param observer 流式事件观察者
     * @return 可取消的生成控制柄
     */
    @Override
    public StreamControl generate(GenerationPrompt prompt, StreamObserver observer) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(observer, "observer");
        long startedNanos = System.nanoTime();
        LOGGER.info(
                "event=rag.llm.start maxOutputTokens={}",
                MAX_OUTPUT_TOKENS);
        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from(prompt.systemPolicy()),
                        UserMessage.from(prompt.userData()))
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .build();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger partialCount = new AtomicInteger();
        AtomicReference<StreamingHandle> providerHandle = new AtomicReference<>();
        AtomicReference<StreamingHandle> deferredProviderCancellation =
                new AtomicReference<>();
        Object lifecycleLock = new Object();
        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    synchronized (lifecycleLock) {
                        if (!terminal.get()) {
                            partialCount.incrementAndGet();
                            observer.onDelta(partialResponse);
                        }
                    }
                } finally {
                    drainDeferredProviderCancellationIfUnlocked(
                            lifecycleLock,
                            deferredProviderCancellation);
                }
            }

            @Override
            public void onPartialResponse(
                    PartialResponse partialResponse,
                    PartialResponseContext context
            ) {
                try {
                    StreamingHandle incomingHandle = context.streamingHandle();
                    providerHandle.compareAndSet(null, incomingHandle);
                    if (cancelled.get()) {
                        cancelProviderHandle(incomingHandle);
                    }
                    synchronized (lifecycleLock) {
                        if (!terminal.get()) {
                            partialCount.incrementAndGet();
                            observer.onDelta(partialResponse.text());
                        }
                    }
                } finally {
                    drainDeferredProviderCancellationIfUnlocked(
                            lifecycleLock,
                            deferredProviderCancellation);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                synchronized (lifecycleLock) {
                    if (!terminal.compareAndSet(false, true)) {
                        return;
                    }
                    String text = completeResponse.aiMessage().text();
                    if (text == null || text.isBlank()) {
                        IllegalStateException error = new IllegalStateException(
                                "Model completed without text");
                        LOGGER.warn(
                                "event=rag.llm.failure causeType={} "
                                        + "partialCount={} elapsedMs={}",
                                error.getClass().getName(),
                                partialCount.get(),
                                elapsedMs(startedNanos));
                        observer.onError(error);
                        return;
                    }
                    LOGGER.info(
                            "event=rag.llm.success partialCount={} "
                                    + "outputCodePoints={} elapsedMs={}",
                            partialCount.get(),
                            text.codePointCount(0, text.length()),
                            elapsedMs(startedNanos));
                    observer.onComplete(text);
                }
            }

            @Override
            public void onError(Throwable error) {
                synchronized (lifecycleLock) {
                    if (terminal.compareAndSet(false, true)) {
                        LOGGER.warn(
                                "event=rag.llm.failure causeType={} "
                                        + "partialCount={} elapsedMs={}",
                                error == null
                                        ? "null"
                                        : error.getClass().getName(),
                                partialCount.get(),
                                elapsedMs(startedNanos));
                        observer.onError(error);
                    }
                }
            }
        };
        try {
            model.chat(request, handler);
        } catch (RuntimeException error) {
            handler.onError(error);
        }
        return new StreamControl() {
            @Override
            public void cancel() {
                boolean reentrant = Thread.holdsLock(lifecycleLock);
                StreamingHandle handle;
                synchronized (lifecycleLock) {
                    if (!terminal.compareAndSet(false, true)) {
                        return;
                    }
                    cancelled.set(true);
                    handle = providerHandle.get();
                    if (reentrant && handle != null) {
                        deferredProviderCancellation.set(handle);
                        handle = null;
                    }
                }
                LOGGER.info(
                        "event=rag.llm.cancel partialCount={} elapsedMs={}",
                        partialCount.get(),
                        elapsedMs(startedNanos));
                if (handle != null) {
                    cancelProviderHandle(handle);
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    private static void drainDeferredProviderCancellationIfUnlocked(
            Object lifecycleLock,
            AtomicReference<StreamingHandle> deferredCancellation
    ) {
        if (Thread.holdsLock(lifecycleLock)) {
            return;
        }
        StreamingHandle handle = deferredCancellation.getAndSet(null);
        if (handle != null) {
            cancelProviderHandle(handle);
        }
    }

    private static void cancelProviderHandle(StreamingHandle handle) {
        if (!handle.isCancelled()) {
            handle.cancel();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(
                0L,
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedNanos));
    }
}

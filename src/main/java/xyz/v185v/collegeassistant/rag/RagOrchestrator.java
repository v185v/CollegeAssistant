package xyz.v185v.collegeassistant.rag;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.chat.AnswerStatus;
import xyz.v185v.collegeassistant.chat.AssistantMessageReservation;
import xyz.v185v.collegeassistant.chat.ChatDeltaDto;
import xyz.v185v.collegeassistant.chat.ChatDoneDto;
import xyz.v185v.collegeassistant.chat.ChatMemoryMessage;
import xyz.v185v.collegeassistant.chat.ChatMessageDto;
import xyz.v185v.collegeassistant.chat.ChatService;
import xyz.v185v.collegeassistant.chat.ChatStreamMetaDto;
import xyz.v185v.collegeassistant.chat.ChatStreamRequest;
import xyz.v185v.collegeassistant.chat.SourceCitationDto;
import xyz.v185v.collegeassistant.chat.StartedQuestion;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.profile.ProfileService;
import xyz.v185v.collegeassistant.profile.UserProfileDto;

/**
 * RAG 流式问答总编排器。
 *
 * <p>串联用户消息入库、意图识别、画像读取、双池检索、引用映射、提示构建、
 * 模型流式生成和助手消息终态落库，并统一处理无证据、失败与客户端取消。</p>
 */
@Service
public class RagOrchestrator {

    private static final String ACTION_EVENT_SINK_DELTA = "eventSink.delta";
    private static final String ACTION_EVENT_SINK_ERROR = "eventSink.error";
    private static final String ACTION_EVENT_SINK_COMPLETE = "eventSink.complete";
    private static final String NO_EVIDENCE_ANSWER =
            "未找到权威依据，建议咨询教务处或查看最新官方通知。";
    private final ChatService chatService;
    private final ProfileService profileService;
    private final IntentClassifier intentClassifier;
    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final StreamingGenerationGateway generationGateway;
    private final ExecutorService executor;
    private final SourceCitationMapper sourceCitationMapper =
            new SourceCitationMapper();

    public RagOrchestrator(
            ChatService chatService,
            ProfileService profileService,
            IntentClassifier intentClassifier,
            RetrievalService retrievalService,
            PromptBuilder promptBuilder,
            StreamingGenerationGateway generationGateway,
            @Qualifier("chatStreamExecutor") ExecutorService executor
    ) {
        this.chatService = Objects.requireNonNull(chatService, "chatService");
        this.profileService = Objects.requireNonNull(profileService, "profileService");
        this.intentClassifier = Objects.requireNonNull(
                intentClassifier, "intentClassifier");
        this.retrievalService = Objects.requireNonNull(
                retrievalService, "retrievalService");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.generationGateway = Objects.requireNonNull(
                generationGateway, "generationGateway");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * 启动一次异步流式问答。
     *
     * @param principal 当前登录用户
     * @param request   已校验的聊天请求
     * @param eventSink 传输层事件接收端
     * @return 可取消完整问答生命周期的控制柄
     */
    public ChatStreamControl start(
            AuthPrincipal principal,
            ChatStreamRequest request,
            ChatEventSink eventSink
    ) {
        Objects.requireNonNull(eventSink, "eventSink");
        ChatStreamRequest validated = Objects.requireNonNull(request, "request")
                .validated();
        List<ChatMemoryMessage> memory = List.of();
        if (Boolean.TRUE.equals(validated.useMemory())
                && validated.sessionId() != null) {
            memory = chatService.getMemory(principal, validated.sessionId());
        }
        StartedQuestion started = chatService.startQuestion(
                principal, validated.sessionId(), validated.question());
        AssistantMessageReservation reservation =
                chatService.reserveAssistantMessage(
                        principal, started.sessionId());
        ChatStreamLifecycle control = new ChatStreamLifecycle(() ->
                chatService.discardAssistantPlaceholder(
                        principal,
                        started.sessionId(),
                        reservation.assistantMessageId()));
        try {
            List<ChatMemoryMessage> capturedMemory = memory;
            control.attachWorker(executor.submit(() -> runSafely(
                    principal,
                    validated,
                    started,
                    reservation,
                    capturedMemory,
                    eventSink,
                    control)));
        } catch (RejectedExecutionException exception) {
            terminateWithFailure(
                    control,
                    eventSink,
                    ErrorCode.INTERNAL_ERROR,
                    "服务器内部错误");
        }
        return control;
    }

    private void runSafely(
            AuthPrincipal principal,
            ChatStreamRequest request,
            StartedQuestion started,
            AssistantMessageReservation reservation,
            List<ChatMemoryMessage> memory,
            ChatEventSink eventSink,
            ChatStreamLifecycle control
    ) {
        try {
            runNoEvidenceCapableFlow(
                    principal,
                    request,
                    started,
                    reservation,
                    memory,
                    eventSink,
                    control);
        } catch (RuntimeException exception) {
            terminateWithFailure(
                    control,
                    eventSink,
                    ErrorCode.INTERNAL_ERROR,
                    "服务器内部错误");
        }
    }

    private void runNoEvidenceCapableFlow(
            AuthPrincipal principal,
            ChatStreamRequest request,
            StartedQuestion started,
            AssistantMessageReservation reservation,
            List<ChatMemoryMessage> memory,
            ChatEventSink eventSink,
            ChatStreamLifecycle control
    ) {
        if (!control.isActive()) {
            return;
        }
        IntentDto intent = intentClassifier.classify(request.question());
        if (!control.isActive()) {
            return;
        }
        UserProfileDto profile = profileService.getProfile(principal.userId());
        if (!control.isActive()) {
            return;
        }
        List<RetrievedChunk> chunks = retrievalService.retrieve(
                new RetrievalRequest(
                        request.question(), profile, intent, request.maxSources()));
        if (!control.emitIfActive(() -> eventSink.meta(new ChatStreamMetaDto(
                started.sessionId(),
                reservation.assistantMessageId(),
                intent)))) {
            return;
        }
        EvidenceSet evidence = sourceCitationMapper.map(
                chunks, request.maxSources());
        if (!control.isActive()) {
            return;
        }
        if (evidence.isEmpty()) {
            completeWithoutEvidence(
                    principal, started, reservation, eventSink, control);
            return;
        }
        GenerationPrompt prompt = promptBuilder.build(
                profile,
                memory,
                evidence.officialChunks(),
                evidence.ugcChunks(),
                request.question());
        if (!control.isActive()) {
            return;
        }
        AnswerBudget answerBudget = new AnswerBudget();
        StreamControl providerControl = generationGateway.generate(
                prompt,
                new StreamObserver() {
                    @Override
                    public void onDelta(String text) {
                        if (!answerBudget.tryAdd(text)) {
                            terminateWithFailure(
                                    control,
                                    eventSink,
                                    ErrorCode.MODEL_PROVIDER_FAILED,
                                    "模型服务调用失败");
                            return;
                        }
                        try {
                            control.emitIfActive(() ->
                                    eventSink.delta(new ChatDeltaDto(text)));
                        } catch (RuntimeException exception) {
                            ChatStreamLifecycle.logBestEffortFailure(
                                    ACTION_EVENT_SINK_DELTA, exception);
                            terminateWithFailure(
                                    control,
                                    eventSink,
                                    ErrorCode.INTERNAL_ERROR,
                                    "服务器内部错误");
                        }
                    }

                    @Override
                    public void onComplete(String finalText) {
                        if (!control.beginCompletion()) {
                            return;
                        }
                        try {
                            String validatedFinalText =
                                    answerBudget.requireValidFinal(finalText);
                            ChatMessageDto persisted =
                                    chatService.finalizeAssistantMessage(
                                            principal,
                                            started.sessionId(),
                                            reservation.assistantMessageId(),
                                            validatedFinalText,
                                            evidence.citations());
                            for (SourceCitationDto source : persisted.sources()) {
                                eventSink.source(source);
                            }
                            eventSink.done(new ChatDoneDto(
                                    started.sessionId(),
                                    reservation.assistantMessageId(),
                                    AnswerStatus.ANSWERED,
                                    persisted.content(),
                                    persisted.sources()));
                            eventSink.complete();
                        } catch (RuntimeException exception) {
                            boolean modelOutputFailure = exception
                                    instanceof AnswerBudget.InvalidFinalAnswerException;
                            terminateAfterCompletionFailure(
                                    control,
                                    eventSink,
                                    modelOutputFailure
                                            ? ErrorCode.MODEL_PROVIDER_FAILED
                                            : ErrorCode.INTERNAL_ERROR,
                                    modelOutputFailure
                                            ? "模型服务调用失败"
                                            : "服务器内部错误");
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        terminateWithFailure(
                                control,
                                eventSink,
                                ErrorCode.MODEL_PROVIDER_FAILED,
                                "模型服务调用失败");
                    }
                });
        control.attachProvider(providerControl);
    }

    private void completeWithoutEvidence(
            AuthPrincipal principal,
            StartedQuestion started,
            AssistantMessageReservation reservation,
            ChatEventSink eventSink,
            ChatStreamLifecycle control
    ) {
        if (!control.beginCompletion()) {
            return;
        }
        try {
            ChatMessageDto persisted = chatService.finalizeAssistantMessage(
                    principal,
                    started.sessionId(),
                    reservation.assistantMessageId(),
                    NO_EVIDENCE_ANSWER,
                    List.of());
            eventSink.done(new ChatDoneDto(
                    started.sessionId(),
                    reservation.assistantMessageId(),
                    AnswerStatus.NO_EVIDENCE,
                    persisted.content(),
                    persisted.sources()));
            eventSink.complete();
        } catch (RuntimeException exception) {
            terminateAfterCompletionFailure(
                    control,
                    eventSink,
                    ErrorCode.INTERNAL_ERROR,
                    "服务器内部错误");
        }
    }

    private void terminateWithFailure(
            ChatStreamLifecycle control,
            ChatEventSink eventSink,
            int errorCode,
            String message
    ) {
        if (control.fail()) {
            emitFailureTerminal(eventSink, errorCode, message);
        }
    }

    private void terminateAfterCompletionFailure(
            ChatStreamLifecycle control,
            ChatEventSink eventSink,
            int errorCode,
            String message
    ) {
        if (control.failAfterCompletion()) {
            emitFailureTerminal(eventSink, errorCode, message);
        }
    }

    private void emitFailureTerminal(
            ChatEventSink eventSink,
            int errorCode,
            String message
    ) {
        ApiResponse<Void> error = ApiResponse.error(errorCode, message);
        ChatStreamLifecycle.runBestEffort(
                ACTION_EVENT_SINK_ERROR,
                () -> eventSink.error(error));
        ChatStreamLifecycle.runBestEffort(
                ACTION_EVENT_SINK_COMPLETE,
                eventSink::complete);
    }

}

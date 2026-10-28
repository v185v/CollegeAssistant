package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import tools.jackson.databind.json.JsonMapper;

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
import xyz.v185v.collegeassistant.chat.SourceType;
import xyz.v185v.collegeassistant.chat.StartedQuestion;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.profile.ProfileService;
import xyz.v185v.collegeassistant.profile.UserProfileDto;

class RagOrchestratorTest {

    private static final JsonMapper TEST_JSON_MAPPER =
            JsonMapper.builder().findAndAddModules().build();
    private static final String NO_EVIDENCE_ANSWER =
            "未找到权威依据，建议咨询教务处或查看最新官方通知。";
    private static final AuthPrincipal PRINCIPAL =
            new AuthPrincipal(7L, "student", "USER");
    private static final IntentDto INTENT =
            new IntentDto(IntentType.OFFICIAL, 0.91);

    @Test
    void noEvidenceSkipsModelAndPersistsFixedAnswerBeforeDone() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        UserProfileDto profile = emptyProfile();
        ChatMessageDto userMessage = message(51L, "USER", "绩点怎么算？");
        ChatMessageDto assistantMessage =
                message(52L, "ASSISTANT", NO_EVIDENCE_ANSWER);
        when(chatService.startQuestion(PRINCIPAL, null, "绩点怎么算？"))
                .thenReturn(new StartedQuestion(12L, userMessage));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(profile);
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of());
        when(chatService.finalizeAssistantMessage(
                eq(PRINCIPAL), eq(12L), eq(52L),
                eq(NO_EVIDENCE_ANSWER), eq(List.of())))
                .thenAnswer(invocation -> {
                    sink.events.add("finalize");
                    return assistantMessage;
                });
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "绩点怎么算？", null, null),
                sink);

        assertThat(generationGateway.calls).isZero();
        verifyNoInteractions(promptBuilder);
        assertThat(sink.events)
                .containsExactly("meta", "finalize", "done", "complete");
        assertThat(sink.meta)
                .isEqualTo(new ChatStreamMetaDto(12L, 52L, INTENT));
        assertThat(sink.done).isEqualTo(new ChatDoneDto(
                12L,
                52L,
                AnswerStatus.NO_EVIDENCE,
                NO_EVIDENCE_ANSWER,
                List.of()));
        assertThat(sink.sources).isEmpty();
        assertThat(sink.deltas).isEmpty();
        assertThat(sink.errors).isEmpty();
    }

    @Test
    void cancellationWhileQueuedCancelsWorkerAndDiscardsReservation() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        QueueingExecutorService executor = new QueueingExecutorService();
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                executor);

        ChatStreamControl control = orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);
        control.cancel();

        assertThat(executor.queuedFuture().isCancelled()).isTrue();
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(sink.events).isEmpty();
        verifyNoInteractions(profileService, retrievalService, promptBuilder);
        assertThat(generationGateway.calls).isZero();
    }

    @Test
    void cancellationInterruptsRunningWorkerAndDiscardsReservation()
            throws Exception {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenAnswer(invocation -> {
            workerEntered.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException exception) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker interrupted", exception);
            }
            return emptyProfile();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                executor);

        try {
            ChatStreamControl control = orchestrator.start(
                    PRINCIPAL,
                    new ChatStreamRequest(null, "问题", false, 8),
                    sink);
            assertThat(workerEntered.await(2, TimeUnit.SECONDS)).isTrue();

            control.cancel();
            boolean interrupted =
                    workerInterrupted.await(500, TimeUnit.MILLISECONDS);

            assertThat(interrupted).isTrue();
            assertThat(control.isCancelled()).isTrue();
            verify(chatService)
                    .discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
            assertThat(sink.events).isEmpty();
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellationDuringRetrievalStopsBeforeMetaAndModelEvenWhenInterruptIsIgnored()
            throws Exception {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        CountDownLatch retrievalEntered = new CountDownLatch(1);
        CountDownLatch releaseRetrieval = new CountDownLatch(1);
        when(chatService.startQuestion(PRINCIPAL, null, "Question"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "Question")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenAnswer(invocation -> {
                    retrievalEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = releaseRetrieval.await(
                                    2, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
// 特意忽略取消，以模拟仍会返回成功响应的依赖。
                        }
                    }
                    return List.of(new RetrievedChunk(
                            301L,
                            "Official evidence",
                            "OFFICIAL",
                            0.87,
                            "https://example.edu/rule.html",
                            "Rule",
                            "Registrar",
                            null,
                            Map.of()));
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                executor);

        try {
            ChatStreamControl control = orchestrator.start(
                    PRINCIPAL,
                    new ChatStreamRequest(null, "Question", false, 8),
                    sink);
            assertThat(retrievalEntered.await(2, TimeUnit.SECONDS)).isTrue();

            control.cancel();
            releaseRetrieval.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

            assertThat(sink.events).isEmpty();
            assertThat(generationGateway.calls).isZero();
            verifyNoInteractions(promptBuilder);
            verify(chatService)
                    .discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        } finally {
            releaseRetrieval.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void executorRejectionDiscardsReservationAndEndsStreamSafely() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                new RejectingExecutorService());

        Throwable escaped = catchThrowable(() -> orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink));

        assertThat(escaped).isNull();
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(sink.events).containsExactly("error", "complete");
        assertThat(sink.errors).containsExactly(ApiResponse.error(
                ErrorCode.INTERNAL_ERROR, "服务器内部错误"));
        verifyNoInteractions(profileService, retrievalService, promptBuilder);
        assertThat(generationGateway.calls).isZero();
    }

    @Test
    void officialEvidenceStreamsThenPersistsBeforeStructuredSourceAndDone() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        UserProfileDto profile = emptyProfile();
        OffsetDateTime publishedAt = OffsetDateTime.parse(
                "2026-06-01T09:00:00+08:00");
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "绩点按课程成绩与学分加权计算。",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "本科生学籍管理办法",
                "教务处",
                publishedAt,
                Map.of("fileObjectKey", "must-not-leak"));
        when(chatService.startQuestion(PRINCIPAL, null, "绩点怎么算？"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "绩点怎么算？")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(profile);
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        when(chatService.finalizeAssistantMessage(
                eq(PRINCIPAL), eq(12L), eq(52L),
                eq("根据官方规定回答。"), any()))
                .thenAnswer(invocation -> {
                    sink.events.add("finalize");
                    List<SourceCitationDto> persisted = invocation.getArgument(4);
                    return new ChatMessageDto(
                            52L,
                            "ASSISTANT",
                            "根据官方规定回答。",
                            persisted,
                            OffsetDateTime.parse("2026-07-14T12:00:00+08:00"));
                });
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "绩点怎么算？", false, 8),
                sink);

        assertThat(generationGateway.calls).isEqualTo(1);
        generationGateway.emitDelta("根据官方规定");
        generationGateway.emitComplete("根据官方规定回答。");

        SourceCitationDto expectedSource = new SourceCitationDto(
                301L,
                SourceType.OFFICIAL,
                "本科生学籍管理办法",
                "https://example.edu/rule.html",
                "教务处",
                publishedAt,
                0.87,
                "绩点按课程成绩与学分加权计算。");
        assertThat(sink.events).containsExactly(
                "meta", "delta", "finalize", "source", "done", "complete");
        assertThat(sink.deltas)
                .containsExactly(new ChatDeltaDto("根据官方规定"));
        assertThat(sink.sources).containsExactly(expectedSource);
        assertThat(sink.done).isEqualTo(new ChatDoneDto(
                12L,
                52L,
                AnswerStatus.ANSWERED,
                "根据官方规定回答。",
                List.of(expectedSource)));
        assertThat(generationGateway.prompt.systemPolicy())
                .doesNotContain("must-not-leak");
        assertThat(generationGateway.prompt.userData())
                .doesNotContain("fileObjectKey", "must-not-leak");
        assertThat(sink.errors).isEmpty();
    }

    @Test
    void existingMemorySessionIsCheckedBeforeWritesAndIntentPrecedesProfile() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        IntentClassifier intentClassifier = mock(IntentClassifier.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "Rule",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule",
                "Evidence",
                "Office",
                null,
                Map.of());
        List<ChatMemoryMessage> existing = List.of(
                new ChatMemoryMessage("ASSISTANT", "旧回答"));
        when(chatService.getMemory(PRINCIPAL, 12L)).thenReturn(existing);
        when(chatService.startQuestion(PRINCIPAL, 12L, "继续问"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "继续问")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(intentClassifier.classify("继续问")).thenReturn(INTENT);
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        when(promptBuilder.build(any(), any(), any(), any(), any()))
                .thenReturn(new GenerationPrompt("policy", "data"));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                intentClassifier,
                retrievalService,
                promptBuilder,
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(12L, "继续问", true, 8),
                sink);

        InOrder flow = org.mockito.Mockito.inOrder(
                chatService, intentClassifier, profileService, retrievalService);
        flow.verify(chatService).getMemory(PRINCIPAL, 12L);
        flow.verify(chatService).startQuestion(PRINCIPAL, 12L, "继续问");
        flow.verify(chatService).reserveAssistantMessage(PRINCIPAL, 12L);
        flow.verify(intentClassifier).classify("继续问");
        flow.verify(profileService).getProfile(7L);
        flow.verify(retrievalService).retrieve(any(RetrievalRequest.class));
        verify(promptBuilder).build(
                any(), eq(existing), any(), any(), eq("继续问"));
    }

    @Test
    void modelErrorDiscardsReservationAndEmitsOnlySafeErrorTerminal() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "依据",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "规定",
                "教务处",
                null,
                Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);
        generationGateway.emitError(new RuntimeException(
                "secret provider response body"));

        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(generationGateway.control.cancelled).isTrue();
        assertThat(sink.events).containsExactly("meta", "error", "complete");
        assertThat(sink.errors).containsExactly(ApiResponse.error(
                ErrorCode.MODEL_PROVIDER_FAILED, "模型服务调用失败"));
        assertThat(sink.done).isNull();
        assertThat(sink.sources).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void modelErrorAttemptsEveryCleanupAndTerminalActionWhenEachThrows(
            CapturedOutput output
    ) {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ThrowingModelControl providerControl = new ThrowingModelControl();
        FakeGenerationGateway generationGateway =
                new FakeGenerationGateway(providerControl);
        ThrowingTerminalEventSink sink = new ThrowingTerminalEventSink();
        ThrowingCancelExecutorService executor =
                new ThrowingCancelExecutorService();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "prompt-evidence-secret",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "rule",
                "registrar",
                null,
                Map.of());
        when(chatService.startQuestion(
                PRINCIPAL, null, "prompt-question-secret"))
                .thenReturn(new StartedQuestion(
                        12L, message(
                                51L, "USER", "prompt-question-secret")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        doThrow(new RuntimeException("discard-secret"))
                .when(chatService)
                .discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                executor);
        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(
                        null, "prompt-question-secret", false, 8),
                sink);

        Throwable escaped = catchThrowable(() -> generationGateway.emitError(
                new RuntimeException("model-body-secret")));

        assertThat(escaped).isNull();
        assertThat(executor.cancelCalls).isEqualTo(1);
        assertThat(providerControl.cancelCalls).isEqualTo(1);
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(sink.errorCalls).isEqualTo(1);
        assertThat(sink.completeCalls).isEqualTo(1);
        assertThat(output.getAll())
                .contains(
                        "action=worker.cancel causeType=java.lang.RuntimeException",
                        "action=provider.cancel causeType=java.lang.RuntimeException",
                        "action=assistant.discard causeType=java.lang.RuntimeException",
                        "action=eventSink.error causeType=java.lang.RuntimeException",
                        "action=eventSink.complete causeType=java.lang.RuntimeException")
                .doesNotContain(
                        "worker-cancel-secret",
                        "provider-cancel-secret",
                        "discard-secret",
                        "error-sink-secret",
                        "complete-sink-secret",
                        "model-body-secret",
                        "prompt-evidence-secret",
                        "prompt-question-secret",
                        generationGateway.prompt.systemPolicy(),
                        generationGateway.prompt.userData());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void deltaSinkFailureSafelyTerminatesAndSuppressesLateCallbacks(
            CapturedOutput output
    ) {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        ThrowingDeltaAndTerminalEventSink sink =
                new ThrowingDeltaAndTerminalEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "prompt-evidence-secret",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "rule",
                "registrar",
                null,
                Map.of());
        when(chatService.startQuestion(
                PRINCIPAL, null, "prompt-question-secret"))
                .thenReturn(new StartedQuestion(
                        12L, message(
                                51L, "USER", "prompt-question-secret")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());
        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(
                        null, "prompt-question-secret", false, 8),
                sink);

        Throwable escaped = catchThrowable(() -> generationGateway.emitDelta(
                "delta-content-secret"));
        Throwable lateEscaped = catchThrowable(() -> {
            generationGateway.emitDelta("late-delta-secret");
            generationGateway.emitComplete("late-final-secret");
            generationGateway.emitError(new RuntimeException(
                    "late-model-body-secret"));
        });

        assertThat(escaped).isNull();
        assertThat(lateEscaped).isNull();
        assertThat(sink.deltaCalls).isEqualTo(1);
        assertThat(sink.errorCalls).isEqualTo(1);
        assertThat(sink.completeCalls).isEqualTo(1);
        assertThat(sink.attemptedError.code()).isEqualTo(
                ErrorCode.INTERNAL_ERROR);
        assertThat(generationGateway.control.cancelled).isTrue();
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(output.getAll())
                .contains(
                        "action=eventSink.delta causeType=java.lang.RuntimeException",
                        "action=eventSink.error causeType=java.lang.RuntimeException",
                        "action=eventSink.complete causeType=java.lang.RuntimeException")
                .doesNotContain(
                        "delta-content-secret",
                        "late-delta-secret",
                        "late-final-secret",
                        "late-model-body-secret",
                        "delta-sink-secret",
                        "error-sink-secret",
                        "complete-sink-secret",
                        "prompt-evidence-secret",
                        "prompt-question-secret",
                        generationGateway.prompt.systemPolicy(),
                        generationGateway.prompt.userData());
    }

    @Test
    void retrievalFailureDiscardsReservationAndEndsWithSafeError() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenThrow(new RuntimeException("secret database detail"));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);

        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(sink.events).containsExactly("error", "complete");
        assertThat(sink.errors).containsExactly(ApiResponse.error(
                ErrorCode.INTERNAL_ERROR, "服务器内部错误"));
        assertThat(generationGateway.calls).isZero();
        verifyNoInteractions(promptBuilder);
    }

    @Test
    void finalizeFailureCleansUpAndNeverEmitsSourceOrDone() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "依据",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "规定",
                "教务处",
                null,
                Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        when(chatService.finalizeAssistantMessage(
                eq(PRINCIPAL), eq(12L), eq(52L), eq("answer"), any()))
                .thenThrow(new RuntimeException("secret database detail"));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());
        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);

        Throwable escaped = catchThrowable(
                () -> generationGateway.emitComplete("answer"));

        assertThat(escaped).isNull();
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(generationGateway.control.cancelled).isTrue();
        assertThat(sink.events).containsExactly("meta", "error", "complete");
        assertThat(sink.errors).containsExactly(ApiResponse.error(
                ErrorCode.INTERNAL_ERROR, "服务器内部错误"));
        assertThat(sink.sources).isEmpty();
        assertThat(sink.done).isNull();
    }

    @Test
    void cancellationCannotOvertakeAnInFlightDelta() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        BlockingDeltaEventSink sink = new BlockingDeltaEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "依据",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "规定",
                "教务处",
                null,
                Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());
        ChatStreamControl control = orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);
        CountDownLatch cancelReturned = new CountDownLatch(1);
        Thread deltaThread = new Thread(
                () -> generationGateway.emitDelta("chunk"));
        Thread cancelThread = new Thread(() -> {
            control.cancel();
            cancelReturned.countDown();
        });

        deltaThread.start();
        assertThat(sink.deltaEntered.await(2, TimeUnit.SECONDS)).isTrue();
        cancelThread.start();
        boolean cancellationOvertookDelta =
                cancelReturned.await(200, TimeUnit.MILLISECONDS);
        sink.releaseDelta.countDown();
        deltaThread.join(2_000);
        cancelThread.join(2_000);

        assertThat(cancellationOvertookDelta).isFalse();
        assertThat(((RecordingEventSink) sink).events)
                .containsExactly("meta", "delta");
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(generationGateway.control.cancelled).isTrue();
    }

    @Test
    void noEvidenceCommitWinsOverConcurrentCancellation() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        CountDownLatch finalizeEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalize = new CountDownLatch(1);
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of());
        when(chatService.finalizeAssistantMessage(
                eq(PRINCIPAL), eq(12L), eq(52L),
                eq(NO_EVIDENCE_ANSWER), eq(List.of())))
                .thenAnswer(invocation -> {
                    finalizeEntered.countDown();
                    releaseFinalize.await();
                    return message(52L, "ASSISTANT", NO_EVIDENCE_ANSWER);
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                executor);

        ChatStreamControl control = orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);
        assertThat(finalizeEntered.await(2, TimeUnit.SECONDS)).isTrue();
        control.cancel();
        releaseFinalize.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        verify(chatService, never())
                .discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(control.isCancelled()).isFalse();
        assertThat(sink.events).containsExactly("meta", "done", "complete");
    }

    @Test
    void cancellationDoesNotDeadlockAgainstProviderPartialCallback()
            throws Exception {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        LockingGenerationGateway generationGateway =
                new LockingGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "依据",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "规定",
                "教务处",
                null,
                Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());
        ChatStreamControl control = orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);
        Thread deltaThread = new Thread(
                generationGateway::emitDeltaWhileHoldingProviderLock);
        Thread cancelThread = new Thread(control::cancel);
        deltaThread.setDaemon(true);
        cancelThread.setDaemon(true);

        deltaThread.start();
        assertThat(generationGateway.providerLockHeld.await(
                2, TimeUnit.SECONDS)).isTrue();
        cancelThread.start();
        assertThat(generationGateway.cancelCalled.await(
                2, TimeUnit.SECONDS)).isTrue();
        generationGateway.allowObserverCall.countDown();
        deltaThread.join(500);
        cancelThread.join(500);

        assertThat(deltaThread.isAlive()).isFalse();
        assertThat(cancelThread.isAlive()).isFalse();
    }

    @Test
    void oversizedUtf8FinalAnswerIsRejectedBeforePersistence() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "依据",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "规定",
                "教务处",
                null,
                Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());
        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);

        generationGateway.emitComplete("😀".repeat(16_385));

        verify(chatService, never()).finalizeAssistantMessage(
                any(), any(), any(), any(), any());
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(generationGateway.control.cancelled).isTrue();
        assertThat(sink.events).containsExactly("meta", "error", "complete");
        assertThat(sink.errors).containsExactly(ApiResponse.error(
                ErrorCode.MODEL_PROVIDER_FAILED, "模型服务调用失败"));
    }

    @Test
    void cumulativeUtf8DeltaLimitStopsStreamBeforeOversizedChunk() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L,
                "依据",
                "OFFICIAL",
                0.87,
                "https://example.edu/rule.html",
                "规定",
                "教务处",
                null,
                Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());
        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);
        String first = "😀".repeat(10_000);
        String overflow = "😀".repeat(7_000);

        generationGateway.emitDelta(first);
        generationGateway.emitDelta(overflow);

        assertThat(sink.deltas.size()).isEqualTo(1);
        assertThat(sink.deltas.getFirst().content()).isSameAs(first);
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(generationGateway.control.cancelled).isTrue();
        assertThat(sink.events).containsExactly(
                "meta", "delta", "error", "complete");
        assertThat(sink.errors).containsExactly(ApiResponse.error(
                ErrorCode.MODEL_PROVIDER_FAILED, "模型服务调用失败"));
    }

    @Test
    void mixedEvidenceIsPartitionedAndCappedWithOfficialSourcesFirst() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        UserProfileDto profile = emptyProfile();
        RetrievedChunk ugcA = new RetrievedChunk(
                401L, "经验一", "UGC", 0.81, null,
                "经验帖一", null, null,
                Map.of("fileObjectKey", "private/ugc-401.txt"));
        RetrievedChunk officialA = new RetrievedChunk(
                301L, "规定一", "OFFICIAL", 0.91,
                "https://example.edu/a", "规定一", "教务处", null, Map.of());
        RetrievedChunk ugcB = new RetrievedChunk(
                402L, "经验二", "UGC", 0.80, null,
                "经验帖二", null, null, Map.of());
        RetrievedChunk officialB = new RetrievedChunk(
                302L, "规定二", "OFFICIAL", 0.90,
                "https://example.edu/b", "规定二", "研究生院", null, Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "混合问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "混合问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(profile);
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(ugcA, officialA, ugcB, officialB));
        when(promptBuilder.build(any(), any(), any(), any(), any()))
                .thenReturn(new GenerationPrompt("policy", "data"));
        when(chatService.finalizeAssistantMessage(
                eq(PRINCIPAL), eq(12L), eq(52L), eq("answer"), any()))
                .thenAnswer(invocation -> {
                    sink.events.add("finalize");
                    List<SourceCitationDto> persisted = invocation.getArgument(4);
                    return new ChatMessageDto(
                            52L, "ASSISTANT", "answer", persisted,
                            OffsetDateTime.parse("2026-07-14T12:00:00+08:00"));
                });
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> new IntentDto(IntentType.MIXED, 0.8),
                retrievalService,
                promptBuilder,
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "混合问题", false, 3),
                sink);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RetrievedChunk>> officialCaptor =
                ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RetrievedChunk>> ugcCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(promptBuilder).build(
                eq(profile), eq(List.of()),
                officialCaptor.capture(), ugcCaptor.capture(), eq("混合问题"));
        assertThat(officialCaptor.getValue())
                .containsExactly(officialA, officialB);
        assertThat(ugcCaptor.getValue()).containsExactly(ugcA);

        generationGateway.emitComplete("answer");

        assertThat(sink.sources).extracting(SourceCitationDto::chunkId)
                .containsExactly(301L, 302L, 401L);
        assertThat(sink.sources.getLast().sourceUrl()).isNull();
        assertThat(sink.sources.toString())
                .doesNotContain("fileObjectKey", "private/ugc-401.txt");
        assertThat(sink.events).containsExactly(
                "meta", "finalize", "source", "source", "source",
                "done", "complete");
    }

    @Test
    void invalidOfficialCitationIsExcludedBeforeModelAndFallsBackToNoEvidence() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk invalid = new RetrievedChunk(
                301L,
                "伪造依据",
                "OFFICIAL",
                0.99,
                "javascript:alert(1)",
                "伪造规定",
                "未知机构",
                null,
                Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(invalid));
        when(chatService.finalizeAssistantMessage(
                eq(PRINCIPAL), eq(12L), eq(52L),
                eq(NO_EVIDENCE_ANSWER), eq(List.of())))
                .thenReturn(message(52L, "ASSISTANT", NO_EVIDENCE_ANSWER));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder,
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);

        assertThat(generationGateway.calls).isZero();
        verifyNoInteractions(promptBuilder);
        assertThat(sink.events).containsExactly("meta", "done", "complete");
        assertThat(sink.done.answerStatus()).isEqualTo(AnswerStatus.NO_EVIDENCE);
        assertThat(sink.sources).isEmpty();
    }

    @Test
    void oversizedCodePointFinalAnswerIsRejectedBeforePersistence() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        FakeGenerationGateway generationGateway = new FakeGenerationGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L, "依据", "OFFICIAL", 0.87,
                "https://example.edu/rule.html", "规定", "教务处",
                null, Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());
        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);

        generationGateway.emitComplete("x".repeat(20_001));

        verify(chatService, never()).finalizeAssistantMessage(
                any(), any(), any(), any(), any());
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(sink.errors).containsExactly(ApiResponse.error(
                ErrorCode.MODEL_PROVIDER_FAILED, "模型服务调用失败"));
    }

    @Test
    void synchronousModelFailureCancelsProviderControlReturnedAfterCallback() {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        SynchronouslyFailingGateway generationGateway =
                new SynchronouslyFailingGateway();
        RecordingEventSink sink = new RecordingEventSink();
        RetrievedChunk official = new RetrievedChunk(
                301L, "依据", "OFFICIAL", 0.87,
                "https://example.edu/rule.html", "规定", "教务处",
                null, Map.of());
        when(chatService.startQuestion(PRINCIPAL, null, "问题"))
                .thenReturn(new StartedQuestion(
                        12L, message(51L, "USER", "问题")));
        when(chatService.reserveAssistantMessage(PRINCIPAL, 12L))
                .thenReturn(new AssistantMessageReservation(12L, 52L));
        when(profileService.getProfile(7L)).thenReturn(emptyProfile());
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(official));
        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                question -> INTENT,
                retrievalService,
                promptBuilder(),
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(null, "问题", false, 8),
                sink);

        assertThat(generationGateway.control.cancelled).isTrue();
        verify(chatService).discardAssistantPlaceholder(PRINCIPAL, 12L, 52L);
        assertThat(sink.events).containsExactly("meta", "error", "complete");
    }

    private static UserProfileDto emptyProfile() {
        return new UserProfileDto(
                7L, null, null, null, null, null, null, List.of(), null);
    }

    private static PromptBuilder promptBuilder() {
        return new PromptBuilder(TEST_JSON_MAPPER);
    }

    private static ChatMessageDto message(Long id, String role, String content) {
        return new ChatMessageDto(
                id, role, content, List.of(), OffsetDateTime.now());
    }

    private static final class FakeGenerationGateway
            implements StreamingGenerationGateway {

        private int calls;
        private GenerationPrompt prompt;
        private StreamObserver observer;
        private final RecordingModelControl control;

        private FakeGenerationGateway() {
            this(new RecordingModelControl());
        }

        private FakeGenerationGateway(RecordingModelControl control) {
            this.control = control;
        }

        @Override
        public StreamControl generate(
                GenerationPrompt prompt,
                StreamObserver observer
        ) {
            calls++;
            this.prompt = prompt;
            this.observer = observer;
            return control;
        }

        private void emitDelta(String text) {
            observer.onDelta(text);
        }

        private void emitComplete(String text) {
            observer.onComplete(text);
        }

        private void emitError(Throwable error) {
            observer.onError(error);
        }
    }

    private static class RecordingModelControl implements StreamControl {

        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class ThrowingModelControl
            extends RecordingModelControl {

        private int cancelCalls;

        @Override
        public void cancel() {
            cancelCalls++;
            throw new RuntimeException("provider-cancel-secret");
        }
    }

    private static final class LockingGenerationGateway
            implements StreamingGenerationGateway {

        private final Object providerLock = new Object();
        private final CountDownLatch providerLockHeld = new CountDownLatch(1);
        private final CountDownLatch allowObserverCall = new CountDownLatch(1);
        private final CountDownLatch cancelCalled = new CountDownLatch(1);
        private StreamObserver observer;

        @Override
        public StreamControl generate(
                GenerationPrompt prompt,
                StreamObserver observer
        ) {
            this.observer = observer;
            return new StreamControl() {
                private volatile boolean cancelled;

                @Override
                public void cancel() {
                    cancelCalled.countDown();
                    synchronized (providerLock) {
                        cancelled = true;
                    }
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        private void emitDeltaWhileHoldingProviderLock() {
            synchronized (providerLock) {
                providerLockHeld.countDown();
                try {
                    if (!allowObserverCall.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("observer release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                observer.onDelta("chunk");
            }
        }
    }

    private static final class SynchronouslyFailingGateway
            implements StreamingGenerationGateway {

        private final RecordingModelControl control =
                new RecordingModelControl();

        @Override
        public StreamControl generate(
                GenerationPrompt prompt,
                StreamObserver observer
        ) {
            observer.onError(new RuntimeException("startup failed"));
            return control;
        }
    }

    private static class DirectExecutorService
            extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class ThrowingCancelExecutorService
            extends DirectExecutorService {

        private int cancelCalls;

        @Override
        protected <T> RunnableFuture<T> newTaskFor(
                Runnable runnable,
                T value
        ) {
            return new FutureTask<>(runnable, value) {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    ThrowingCancelExecutorService.this.cancelCalls++;
                    throw new RuntimeException("worker-cancel-secret");
                }
            };
        }
    }

    private static final class QueueingExecutorService
            extends AbstractExecutorService {

        private Runnable queued;
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return queued == null ? List.of() : List.of(queued);
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            queued = command;
        }

        private Future<?> queuedFuture() {
            assertThat(queued).isInstanceOf(Future.class);
            return (Future<?>) queued;
        }
    }

    private static final class RejectingExecutorService
            extends DirectExecutorService {

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("queue full details");
        }
    }

    private static class RecordingEventSink implements ChatEventSink {

        private final List<String> events = new ArrayList<>();
        private final List<ChatDeltaDto> deltas = new ArrayList<>();
        private final List<SourceCitationDto> sources = new ArrayList<>();
        private final List<ApiResponse<Void>> errors = new ArrayList<>();
        private ChatStreamMetaDto meta;
        private ChatDoneDto done;

        @Override
        public void meta(ChatStreamMetaDto event) {
            events.add("meta");
            meta = event;
        }

        @Override
        public void delta(ChatDeltaDto event) {
            events.add("delta");
            deltas.add(event);
        }

        @Override
        public void source(SourceCitationDto event) {
            events.add("source");
            sources.add(event);
        }

        @Override
        public void done(ChatDoneDto event) {
            events.add("done");
            done = event;
        }

        @Override
        public void error(ApiResponse<Void> event) {
            events.add("error");
            errors.add(event);
        }

        @Override
        public void complete() {
            events.add("complete");
        }
    }

    private static class ThrowingTerminalEventSink
            extends RecordingEventSink {

        int errorCalls;
        int completeCalls;
        ApiResponse<Void> attemptedError;

        @Override
        public void error(ApiResponse<Void> event) {
            errorCalls++;
            attemptedError = event;
            throw new RuntimeException("error-sink-secret");
        }

        @Override
        public void complete() {
            completeCalls++;
            throw new RuntimeException("complete-sink-secret");
        }
    }

    private static final class ThrowingDeltaAndTerminalEventSink
            extends ThrowingTerminalEventSink {

        private int deltaCalls;

        @Override
        public void delta(ChatDeltaDto event) {
            deltaCalls++;
            throw new RuntimeException("delta-sink-secret");
        }
    }

    private static final class BlockingDeltaEventSink
            extends RecordingEventSink {

        private final CountDownLatch deltaEntered = new CountDownLatch(1);
        private final CountDownLatch releaseDelta = new CountDownLatch(1);

        @Override
        public void delta(ChatDeltaDto event) {
            deltaEntered.countDown();
            try {
                if (!releaseDelta.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("delta release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            super.delta(event);
        }
    }
}

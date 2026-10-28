package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.chat.AnswerStatus;
import xyz.v185v.collegeassistant.chat.AssistantMessageReservation;
import xyz.v185v.collegeassistant.chat.ChatDeltaDto;
import xyz.v185v.collegeassistant.chat.ChatDoneDto;
import xyz.v185v.collegeassistant.chat.ChatMessageDto;
import xyz.v185v.collegeassistant.chat.ChatService;
import xyz.v185v.collegeassistant.chat.ChatStreamMetaDto;
import xyz.v185v.collegeassistant.chat.ChatStreamRequest;
import xyz.v185v.collegeassistant.chat.SourceCitationDto;
import xyz.v185v.collegeassistant.chat.SourceType;
import xyz.v185v.collegeassistant.chat.StartedQuestion;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkSearchCriteria;
import xyz.v185v.collegeassistant.document.EmbeddingChunkSearchResult;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.profile.ProfileService;
import xyz.v185v.collegeassistant.profile.UserProfileDto;

class RagRegressionTest {

    private static final String CASES_RESOURCE =
            "/rag/evaluation-cases.json";
    private static final String OFFICIAL_QUESTION = "绩点如何计算？";
    private static final String EXPERIENTIAL_QUESTION =
            "哪个老师微积分好拿分？";
    private static final String UNKNOWN_QUESTION =
            "火星校区奖学金怎么申请？";
    private static final String FIXTURE_ANSWER = "基于确定性评测夹具回答。";
    private static final long SESSION_ID = 101L;
    private static final long USER_MESSAGE_ID = 102L;
    private static final long ASSISTANT_MESSAGE_ID = 103L;
    private static final AuthPrincipal PRINCIPAL =
            new AuthPrincipal(7L, "regression", "USER");
    private static final JsonMapper JSON_MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    @TestFactory
    Stream<DynamicTest> evaluatesFixtureCases() {
        return loadCases().stream()
                .map(testCase -> dynamicTest(testCase.name(), () -> {
                    EvaluationResult result = evaluate(testCase);

                    assertEvaluationResult(testCase, result);
                }));
    }

    @Test
    void rejectsNullCaseList() {
        assertThatThrownBy(() -> validateCases(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluation cases")
                .hasMessageContaining("null");
    }

    @Test
    void rejectsEmptyCaseList() {
        assertThatThrownBy(() -> validateCases(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluation cases")
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsNullCaseElementWithIndex() {
        assertThatThrownBy(() -> validateCases(
                Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0")
                .hasMessageContaining("null");
    }

    @Test
    void rejectsBlankCaseNameWithIndex() {
        assertThatThrownBy(() -> validateCases(List.of(new EvaluationCase(
                " ",
                "问题",
                IntentType.UNKNOWN,
                null,
                AnswerStatus.ANSWERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0")
                .hasMessageContaining("name");
    }

    @Test
    void rejectsBlankQuestionWithIndexAndName() {
        assertThatThrownBy(() -> validateCases(List.of(new EvaluationCase(
                "blank_question",
                "\t",
                IntentType.UNKNOWN,
                null,
                AnswerStatus.ANSWERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0")
                .hasMessageContaining("blank_question")
                .hasMessageContaining("question");
    }

    @Test
    void rejectsNullExpectedIntentWithIndexAndName() {
        assertThatThrownBy(() -> validateCases(List.of(new EvaluationCase(
                "null_intent",
                "问题",
                null,
                null,
                AnswerStatus.ANSWERED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0")
                .hasMessageContaining("null_intent")
                .hasMessageContaining("expectedIntent");
    }

    @Test
    void rejectsNullExpectedAnswerStatusWithIndexAndName() {
        assertThatThrownBy(() -> validateCases(List.of(new EvaluationCase(
                "null_status",
                "问题",
                IntentType.UNKNOWN,
                null,
                null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0")
                .hasMessageContaining("null_status")
                .hasMessageContaining("expectedAnswerStatus");
    }

    @Test
    void rejectsDuplicateNamesWithBothIndexes() {
        assertThatThrownBy(() -> validateCases(List.of(
                validCase("duplicate"),
                validCase("duplicate"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining("index 0")
                .hasMessageContaining("index 1");
    }

    @Test
    void rejectsSourceRequirementForNoEvidence() {
        assertThatThrownBy(() -> validateCases(List.of(new EvaluationCase(
                "no_evidence_source",
                "问题",
                IntentType.UNKNOWN,
                SourceType.OFFICIAL,
                AnswerStatus.NO_EVIDENCE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0")
                .hasMessageContaining("no_evidence_source")
                .hasMessageContaining("mustHaveSourceType")
                .hasMessageContaining("NO_EVIDENCE");
    }

    @Test
    void rejectsSourceRequirementForNonAnsweredStatus() {
        assertThatThrownBy(() -> validateCases(List.of(new EvaluationCase(
                "needs_profile_source",
                "问题",
                IntentType.UNKNOWN,
                SourceType.UGC,
                AnswerStatus.NEED_PROFILE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0")
                .hasMessageContaining("needs_profile_source")
                .hasMessageContaining("mustHaveSourceType")
                .hasMessageContaining("NEED_PROFILE");
    }

    @Test
    void returnsImmutableCaseCopy() {
        List<EvaluationCase> mutable = new ArrayList<>(List.of(
                validCase("immutable_copy")));

        List<EvaluationCase> validated = validateCases(mutable);
        mutable.clear();

        assertThat(validated).hasSize(1);
        assertThatThrownBy(() -> validated.add(validCase("another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void answeredCaseWithoutSourceConstraintAllowsCitations() {
        EvaluationCase testCase = validCase("unconstrained_sources");
        SourceCitationDto citation = new SourceCitationDto(
                999L,
                SourceType.UGC,
                "选课经验",
                null,
                "匿名同学",
                null,
                0.9,
                "经验引用");
        EvaluationResult result = new EvaluationResult(
                testCase.expectedIntent(),
                AnswerStatus.ANSWERED,
                List.of(citation),
                1);

        assertEvaluationResult(testCase, result);
    }

    private List<EvaluationCase> loadCases() {
        try (InputStream input = getClass().getResourceAsStream(CASES_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing classpath resource " + CASES_RESOURCE);
            }
            return validateCases(JSON_MAPPER.readValue(
                    input,
                    new TypeReference<List<EvaluationCase>>() { }));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read RAG evaluation cases", exception);
        }
    }

    private List<EvaluationCase> validateCases(List<EvaluationCase> cases) {
        if (cases == null) {
            throw new IllegalArgumentException(
                    "evaluation cases must not be null");
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "evaluation cases must not be empty");
        }
        Map<String, Integer> firstIndexByName = new HashMap<>();
        for (int index = 0; index < cases.size(); index++) {
            EvaluationCase testCase = cases.get(index);
            if (testCase == null) {
                throw new IllegalArgumentException(
                        "evaluation case at index " + index + " is null");
            }
            if (testCase.name() == null || testCase.name().isBlank()) {
                throw new IllegalArgumentException(
                        "evaluation case at index " + index
                                + " has blank name");
            }
            String context = "evaluation case at index " + index
                    + " (name=" + testCase.name() + ")";
            if (testCase.question() == null || testCase.question().isBlank()) {
                throw new IllegalArgumentException(
                        context + " has blank question");
            }
            if (testCase.expectedIntent() == null) {
                throw new IllegalArgumentException(
                        context + " has null expectedIntent");
            }
            if (testCase.expectedAnswerStatus() == null) {
                throw new IllegalArgumentException(
                        context + " has null expectedAnswerStatus");
            }
            Integer firstIndex = firstIndexByName.putIfAbsent(
                    testCase.name(), index);
            if (firstIndex != null) {
                throw new IllegalArgumentException(
                        "duplicate evaluation case name '" + testCase.name()
                                + "' at index " + index
                                + " (first at index " + firstIndex + ")");
            }
            if (testCase.expectedAnswerStatus() == AnswerStatus.NO_EVIDENCE
                    && testCase.mustHaveSourceType() != null) {
                throw new IllegalArgumentException(
                        context + " must have null mustHaveSourceType when "
                                + "expectedAnswerStatus=NO_EVIDENCE");
            }
            if (testCase.mustHaveSourceType() != null
                    && testCase.expectedAnswerStatus()
                            != AnswerStatus.ANSWERED) {
                throw new IllegalArgumentException(
                        context + " has mustHaveSourceType="
                                + testCase.mustHaveSourceType()
                                + " but expectedAnswerStatus="
                                + testCase.expectedAnswerStatus()
                                + "; a source requirement needs ANSWERED");
            }
        }
        return List.copyOf(cases);
    }

    private EvaluationCase validCase(String name) {
        return new EvaluationCase(
                name,
                "问题",
                IntentType.UNKNOWN,
                null,
                AnswerStatus.ANSWERED);
    }

    private void assertEvaluationResult(
            EvaluationCase testCase,
            EvaluationResult result
    ) {
        assertThat(result.intent())
                .as("%s intent", testCase.name())
                .isEqualTo(testCase.expectedIntent());
        assertThat(result.answerStatus())
                .as("%s answer status", testCase.name())
                .isEqualTo(testCase.expectedAnswerStatus());
        if (testCase.expectedAnswerStatus() == AnswerStatus.NO_EVIDENCE) {
            assertThat(result.modelCalls())
                    .as("%s model calls", testCase.name())
                    .isZero();
            assertThat(result.citations())
                    .as("%s citations", testCase.name())
                    .isEmpty();
        } else {
            assertThat(result.modelCalls())
                    .as("%s model calls", testCase.name())
                    .isOne();
            if (testCase.mustHaveSourceType() != null) {
                assertThat(result.citations())
                        .as("%s citation source types", testCase.name())
                        .extracting(SourceCitationDto::sourceType)
                        .contains(testCase.mustHaveSourceType());
            }
        }
    }

    private EvaluationResult evaluate(EvaluationCase testCase) {
        ChatService chatService = mock(ChatService.class);
        ProfileService profileService = mock(ProfileService.class);
        FakeEmbeddingGateway embeddingGateway = new FakeEmbeddingGateway();
        RetrievalService retrievalService = new RetrievalService(
                embeddingGateway,
                new FakeEmbeddingChunkRepository(),
                new RagProperties(0.35));
        FakeGenerationGateway generationGateway =
                new FakeGenerationGateway();
        RecordingEventSink eventSink = new RecordingEventSink();

        when(chatService.startQuestion(
                PRINCIPAL, null, testCase.question()))
                .thenReturn(new StartedQuestion(
                        SESSION_ID,
                        message(USER_MESSAGE_ID, "USER", testCase.question(), List.of())));
        when(chatService.reserveAssistantMessage(PRINCIPAL, SESSION_ID))
                .thenReturn(new AssistantMessageReservation(
                        SESSION_ID, ASSISTANT_MESSAGE_ID));
        when(profileService.getProfile(PRINCIPAL.userId()))
                .thenReturn(emptyProfile());
        when(chatService.finalizeAssistantMessage(
                eq(PRINCIPAL),
                eq(SESSION_ID),
                eq(ASSISTANT_MESSAGE_ID),
                anyString(),
                anyList()))
                .thenAnswer(invocation -> message(
                        ASSISTANT_MESSAGE_ID,
                        "ASSISTANT",
                        invocation.getArgument(3),
                        invocation.getArgument(4)));

        RagOrchestrator orchestrator = new RagOrchestrator(
                chatService,
                profileService,
                new RuleBasedIntentClassifier(),
                retrievalService,
                new PromptBuilder(JSON_MAPPER),
                generationGateway,
                new DirectExecutorService());

        orchestrator.start(
                PRINCIPAL,
                new ChatStreamRequest(
                        null, testCase.question(), false, 8),
                eventSink);
        if (generationGateway.calls() > 0) {
            generationGateway.complete(FIXTURE_ANSWER);
        }

        if (!eventSink.errors.isEmpty()
                || eventSink.meta == null
                || eventSink.done == null) {
            throw new AssertionError(
                    "RAG evaluation did not complete for " + testCase.name()
                            + "; errors=" + eventSink.errors);
        }
        return new EvaluationResult(
                eventSink.meta.intent().type(),
                eventSink.done.answerStatus(),
                eventSink.done.sources(),
                generationGateway.calls());
    }

    private static UserProfileDto emptyProfile() {
        return new UserProfileDto(
                PRINCIPAL.userId(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null);
    }

    private static ChatMessageDto message(
            Long id,
            String role,
            String content,
            List<SourceCitationDto> sources
    ) {
        return new ChatMessageDto(
                id,
                role,
                content,
                sources,
                OffsetDateTime.parse("2026-07-16T12:00:00+08:00"));
    }

    private static List<Double> unitVector(int index) {
        List<Double> vector = new ArrayList<>(Collections.nCopies(
                EmbeddingDimensions.DIMENSIONS, 0.0));
        vector.set(index, 1.0);
        return List.copyOf(vector);
    }

    private static EmbeddingChunkRecord chunk(
            Long id,
            String docRefType,
            String sourceType,
            String text,
            String sourceUrl,
            String title,
            String sourceOrg,
            Map<String, Object> meta,
            int embeddingIndex
    ) {
        return new EmbeddingChunkRecord(
                id,
                1L,
                docRefType,
                1_000L + id,
                0,
                text,
                unitVector(embeddingIndex),
                sourceType,
                sourceUrl,
                title,
                sourceOrg,
                OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                meta,
                OffsetDateTime.parse("2026-07-02T09:00:00+08:00"));
    }

    private record EvaluationCase(
            String name,
            String question,
            IntentType expectedIntent,
            SourceType mustHaveSourceType,
            AnswerStatus expectedAnswerStatus
    ) {
    }

    private record EvaluationResult(
            IntentType intent,
            AnswerStatus answerStatus,
            List<SourceCitationDto> citations,
            int modelCalls
    ) {
    }

    private static final class FakeEmbeddingGateway
            implements EmbeddingGateway {

        @Override
        public List<Double> embed(String text) {
            return switch (text) {
                case OFFICIAL_QUESTION -> unitVector(0);
                case EXPERIENTIAL_QUESTION -> unitVector(1);
                case UNKNOWN_QUESTION -> unitVector(2);
                default -> throw new AssertionError(
                        "Missing embedding fixture for question: " + text);
            };
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public int dimension() {
            return EmbeddingDimensions.DIMENSIONS;
        }
    }

    private static final class FakeEmbeddingChunkRepository
            extends EmbeddingChunkRepository {

        private static final EmbeddingChunkSearchResult OFFICIAL_RESULT =
                new EmbeddingChunkSearchResult(
                        chunk(
                                201L,
                                "DOCUMENT",
                                "OFFICIAL",
                                "绩点按课程成绩与学分加权计算。",
                                "https://example.edu/academic/gpa",
                                "本科生学籍管理办法",
                                "教务处",
                                Map.of(),
                                0),
                        0.95,
                        DocumentReferenceType.EXTERNAL,
                        1_201L,
                        null);
        private static final EmbeddingChunkSearchResult UGC_RESULT =
                new EmbeddingChunkSearchResult(
                        chunk(
                                301L,
                                "UGC",
                                "UGC",
                                "微积分课程重视平时作业，选课前可参考往届同学经验。",
                                null,
                                "微积分选课经验",
                                "匿名同学",
                                Map.of("likes", 20L, "reports", 0L),
                                1),
                        0.95);

        private FakeEmbeddingChunkRepository() {
            super(null, null);
        }

        @Override
        public List<EmbeddingChunkSearchResult> search(
                EmbeddingChunkSearchCriteria criteria
        ) {
            List<Double> query = criteria.queryEmbedding();
            if ("OFFICIAL".equals(criteria.sourceType())
                    && query.get(0) == 1.0) {
                return List.of(OFFICIAL_RESULT);
            }
            if ("UGC".equals(criteria.sourceType())
                    && query.get(1) == 1.0) {
                return List.of(UGC_RESULT);
            }
            return List.of();
        }
    }

    private static final class FakeGenerationGateway
            implements StreamingGenerationGateway {

        private int calls;
        private StreamObserver observer;

        @Override
        public StreamControl generate(
                GenerationPrompt prompt,
                StreamObserver observer
        ) {
            calls++;
            this.observer = observer;
            return new StreamControl() {
                private boolean cancelled;

                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        private int calls() {
            return calls;
        }

        private void complete(String answer) {
            if (observer == null) {
                throw new AssertionError(
                        "Model completion requested before generation");
            }
            observer.onComplete(answer);
        }
    }

    private static final class DirectExecutorService
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

    private static final class RecordingEventSink implements ChatEventSink {

        private final List<ApiResponse<Void>> errors = new ArrayList<>();
        private ChatStreamMetaDto meta;
        private ChatDoneDto done;

        @Override
        public void meta(ChatStreamMetaDto event) {
            meta = event;
        }

        @Override
        public void delta(ChatDeltaDto event) {
        }

        @Override
        public void source(SourceCitationDto event) {
        }

        @Override
        public void done(ChatDoneDto event) {
            done = event;
        }

        @Override
        public void error(ApiResponse<Void> event) {
            errors.add(event);
        }

        @Override
        public void complete() {
        }
    }
}

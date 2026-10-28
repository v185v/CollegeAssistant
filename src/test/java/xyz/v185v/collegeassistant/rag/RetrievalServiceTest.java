package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.chat.CitationReferenceType;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkSearchCriteria;
import xyz.v185v.collegeassistant.document.EmbeddingChunkSearchResult;
import xyz.v185v.collegeassistant.profile.Degree;
import xyz.v185v.collegeassistant.profile.UserProfileDto;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final String QUESTION = "绩点和老师怎么选";

    @Mock
    private EmbeddingGateway embeddingGateway;

    @Mock
    private EmbeddingChunkRepository chunkRepository;

    private RetrievalService retrievalService;
    private List<Double> queryEmbedding;

    @BeforeEach
    void setUp() {
        retrievalService = new RetrievalService(
                embeddingGateway, chunkRepository, new RagProperties(null));
        queryEmbedding = unitVector();
    }

    @Test
    void officialIntentRanksOfficialEvidenceAheadOfMoreSimilarUgc() {
        givenEmbedding();
        givenPools(
                List.of(result(chunk(10L, "OFFICIAL", Map.of()), 0.80)),
                List.of(result(chunk(20L, "UGC", Map.of()), 0.99)));

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.OFFICIAL, 2));

        assertThat(results).extracting(RetrievedChunk::chunkId).containsExactly(10L, 20L);
        assertThat(results.getFirst().score()).isGreaterThan(results.getLast().score());
    }

    @Test
    void experientialIntentRanksApprovedUgcAheadOfMoreSimilarOfficialEvidence() {
        givenEmbedding();
        givenPools(
                List.of(result(chunk(10L, "OFFICIAL", Map.of()), 0.99)),
                List.of(result(chunk(20L, "UGC", Map.of()), 0.80)));

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.EXPERIENTIAL, 2));

        assertThat(results).extracting(RetrievedChunk::chunkId).containsExactly(20L, 10L);
    }

    @Test
    void mixedIntentBalancesBothPoolsBySimilarity() {
        givenEmbedding();
        givenPools(
                List.of(
                        result(chunk(10L, "OFFICIAL", Map.of()), 0.80),
                        result(chunk(11L, "OFFICIAL", Map.of()), 0.70)),
                List.of(
                        result(chunk(20L, "UGC", Map.of()), 0.90),
                        result(chunk(21L, "UGC", Map.of()), 0.60)));

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.MIXED, 4));

        assertThat(results).extracting(RetrievedChunk::chunkId)
                .containsExactly(20L, 10L, 11L, 21L);
    }

    @Test
    void internalOfficialDocumentsExposeOnlyTheDownloadReference() {
        givenEmbedding();
        EmbeddingChunkRecord internal = chunk(10L, "OFFICIAL", Map.of());
        givenPools(
                List.of(new EmbeddingChunkSearchResult(
                        internal,
                        0.9,
                        DocumentReferenceType.INTERNAL,
                        910L,
                        "https://example.edu/handbook")),
                List.of());

        RetrievedChunk result = retrievalService.retrieve(
                request(IntentType.OFFICIAL, 1)).getFirst();

        assertThat(result.referenceType()).isEqualTo(CitationReferenceType.INTERNAL);
        assertThat(result.documentId()).isEqualTo(910L);
        assertThat(result.sourceUrl()).isNull();
        assertThat(result.externalSourceUrl()).isEqualTo("https://example.edu/handbook");
    }

    @Test
    void unknownIntentUsesDeterministicBalancedConservativeWeights() {
        givenEmbedding();
        givenPools(
                List.of(result(chunk(10L, "OFFICIAL", Map.of()), 1.0)),
                List.of(result(chunk(20L, "UGC", Map.of("likes", 100L)), 1.0)));

        List<RetrievedChunk> first = retrievalService.retrieve(request(IntentType.UNKNOWN, 2));
        List<RetrievedChunk> second = retrievalService.retrieve(request(IntentType.UNKNOWN, 2));

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(RetrievedChunk::chunkId).containsExactly(10L, 20L);
        assertThat(first).allSatisfy(result -> assertThat(result.score()).isBetween(0.0, 0.70));
    }

    @Test
    void questionIsEmbeddedOnceAndBothPoolsReceiveProfileAndGeneralFallbackCriteria() {
        givenEmbedding();
        givenPools(List.of(), List.of());
        UserProfileDto profile = profile(
                2024, "\u2003North Campus\u2003", "\u2003Engineering\u2003");

        retrievalService.retrieve(new RetrievalRequest(
                "  " + QUESTION + "  ", profile, new IntentDto(IntentType.MIXED, 0.9), 3));

        verify(embeddingGateway).embed(QUESTION);
        ArgumentCaptor<EmbeddingChunkSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(EmbeddingChunkSearchCriteria.class);
        verify(chunkRepository, org.mockito.Mockito.times(2)).search(criteriaCaptor.capture());
        assertThat(criteriaCaptor.getAllValues()).extracting(EmbeddingChunkSearchCriteria::sourceType)
                .containsExactly("OFFICIAL", "UGC");
        assertThat(criteriaCaptor.getAllValues()).allSatisfy(criteria -> {
            assertThat(criteria.embeddingConfigId()).isEqualTo(1L);
            assertThat(criteria.queryEmbedding()).isEqualTo(queryEmbedding);
            assertThat(criteria.topK()).isEqualTo(6);
            assertThat(criteria.degree()).isEqualTo("UNDERGRADUATE");
            assertThat(criteria.college()).isEqualTo("Engineering");
            assertThat(criteria.campus()).isEqualTo("North Campus");
            assertThat(criteria.gradeRange()).isEqualTo("2024");
        });
    }

    @Test
    void missingProfileAndBlankProfileStringsDoNotCreateAccidentalFilters() {
        givenEmbedding();
        givenPools(List.of(), List.of());

        retrievalService.retrieve(new RetrievalRequest(
                QUESTION, null, new IntentDto(IntentType.UNKNOWN, 0.2), 1));
        retrievalService.retrieve(new RetrievalRequest(
                QUESTION,
                new UserProfileDto(1L, null, null, " \t", null, "  ", null, List.of(), null),
                new IntentDto(IntentType.UNKNOWN, 0.2),
                1));

        ArgumentCaptor<EmbeddingChunkSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(EmbeddingChunkSearchCriteria.class);
        verify(chunkRepository, org.mockito.Mockito.times(4)).search(criteriaCaptor.capture());
        assertThat(criteriaCaptor.getAllValues()).allSatisfy(criteria -> {
            assertThat(criteria.degree()).isNull();
            assertThat(criteria.college()).isNull();
            assertThat(criteria.campus()).isNull();
            assertThat(criteria.gradeRange()).isNull();
        });
    }

    @Test
    void finalRankingIsStableOnTiesAndTruncatedToTotalTopK() {
        givenEmbedding();
        givenPools(
                List.of(
                        result(chunk(3L, "OFFICIAL", Map.of()), 0.8),
                        result(chunk(1L, "OFFICIAL", Map.of()), 0.8),
                        result(chunk(2L, "OFFICIAL", Map.of()), 0.8)),
                List.of());

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.OFFICIAL, 2));

        assertThat(results).extracting(RetrievedChunk::chunkId).containsExactly(1L, 2L);
    }

    @Test
    void emptyPoolsReturnAnImmutableEmptyResult() {
        givenEmbedding();
        givenPools(List.of(), List.of());

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.MIXED, 5));

        assertThat(results).isEmpty();
        assertThatThrownBy(() -> results.add(retrievedForValidation(1.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void successfulRetrievalLogsOnlyStructuredMetrics(CapturedOutput output) {
        String secretQuestion = "retrieval-question-secret";
        String secretProfile = "retrieval-profile-secret";
        when(embeddingGateway.embed(secretQuestion)).thenReturn(queryEmbedding);
        givenPools(
                List.of(result(chunk(71L, "OFFICIAL", Map.of()), 0.91)),
                List.of(
                        result(chunk(72L, "UGC", Map.of()), 0.90),
                        result(chunk(73L, "UGC", Map.of()), 0.89)));
        RetrievalRequest request = new RetrievalRequest(
                secretQuestion,
                profile(2026, secretProfile, secretProfile),
                new IntentDto(IntentType.MIXED, 0.9),
                3);

        retrievalService.retrieve(request);

        assertThat(output.getAll())
                .contains("event=rag.retrieval.start intent=MIXED topK=3")
                .contains("event=rag.retrieval.success officialCandidates=1 "
                        + "ugcCandidates=2 resultCount=3")
                .containsPattern("event=rag\\.retrieval\\.success .*elapsedMs=\\d+")
                .doesNotContain(secretQuestion, secretProfile);
    }

    @Test
    void nonEmptyPoolsBelowRawSimilarityThresholdReturnAnImmutableEmptyResult() {
        givenEmbedding();
        givenPools(
                List.of(result(chunk(1L, "OFFICIAL", Map.of()), 0.349)),
                List.of(result(chunk(2L, "UGC", Map.of()), 0.10)));

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.MIXED, 2));

        assertThat(results).isEmpty();
        assertThatThrownBy(() -> results.add(retrievedForValidation(1.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void thresholdUsesRawSimilarityBeforePoolWeightAndIncludesExactBoundary() {
        givenEmbedding();
        givenPools(
                List.of(result(chunk(1L, "OFFICIAL", Map.of()), 0.349)),
                List.of(result(chunk(2L, "UGC", Map.of()), 0.35)));

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.OFFICIAL, 2));

        assertThat(results).extracting(RetrievedChunk::chunkId).containsExactly(2L);
        assertThat(results.getFirst().score()).isPositive().isLessThan(0.35);
    }

    @Test
    void zeroSimilarityIsExcludedEvenWhenConfiguredThresholdIsZero() {
        retrievalService = new RetrievalService(
                embeddingGateway, chunkRepository, new RagProperties(0.0));
        givenEmbedding();
        givenPools(
                List.of(result(chunk(1L, "OFFICIAL", Map.of()), 0.0)),
                List.of());

        assertThat(retrievalService.retrieve(request(IntentType.OFFICIAL, 1))).isEmpty();
    }

    @Test
    void nonFiniteAndLowSimilarityScoresAreRemovedWhileHighScoresStillClamp() {
        givenEmbedding();
        givenPools(
                List.of(
                        result(chunk(1L, "OFFICIAL", Map.of()), Double.NaN),
                        result(chunk(2L, "OFFICIAL", Map.of()), Double.POSITIVE_INFINITY),
                        result(chunk(3L, "OFFICIAL", Map.of()), -2.0),
                        result(chunk(4L, "OFFICIAL", Map.of()), 2.0)),
                List.of());

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.OFFICIAL, 4));

        assertThat(results).extracting(RetrievedChunk::chunkId).containsExactly(4L);
        assertThat(scoreOf(results, 4L)).isEqualTo(1.0);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.score()).isFinite();
            assertThat(result.score()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void ugcLikesIncreaseAndReportsDecreaseTrustOnlyWithinSmallBounds() {
        givenEmbedding();
        givenPools(
                List.of(),
                List.of(
                        result(chunk(20L, "UGC", Map.of()), 0.8),
                        result(chunk(21L, "UGC", Map.of("likes", 100L, "reports", 0L)), 0.8),
                        result(chunk(22L, "UGC", Map.of("likes", 0L, "reports", 5L)), 0.8),
                        result(chunk(23L, "UGC", Map.of("likes", "many", "reports", "none")), 0.8),
                        result(chunk(24L, "UGC", Map.of(
                                "likes", Long.MAX_VALUE, "reports", Long.MAX_VALUE)), 0.8)));

        List<RetrievedChunk> results = retrievalService.retrieve(request(IntentType.EXPERIENTIAL, 5));

        double baseline = scoreOf(results, 20L);
        assertThat(scoreOf(results, 21L)).isGreaterThan(baseline);
        assertThat(scoreOf(results, 21L) - baseline).isLessThanOrEqualTo(0.05);
        assertThat(scoreOf(results, 22L)).isLessThan(baseline);
        assertThat(scoreOf(results, 23L)).isEqualTo(baseline);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.score()).isFinite();
            assertThat(result.score()).isBetween(0.0, 0.8);
        });
    }

    @Test
    void retrievedChunkRejectsInvalidFinalScores() {
        assertThatThrownBy(() -> retrievedForValidation(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retrievedForValidation(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retrievedForValidation(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retrievedForValidation(1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retrievedChunkRejectsUnknownSourceTypes() {
        assertThatThrownBy(() -> new RetrievedChunk(
                1L, "Chunk", "OTHER", 0.8, null, "Title", "Source", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceType must be OFFICIAL or UGC");
    }

    @Test
    void retrievedChunkPreservesJsonNullAndDeeplyCopiesMutableMetadata() {
        List<String> tags = new ArrayList<>(List.of("registration"));
        Map<String, Object> audience = new LinkedHashMap<>();
        audience.put("tags", tags);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("optional", null);
        metadata.put("audience", audience);

        RetrievedChunk retrieved = new RetrievedChunk(
                1L, "Chunk", "OFFICIAL", 0.8, "https://example.edu/rules",
                "Rules", "Registrar", OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                metadata);
        tags.add("mutated");
        audience.put("new", true);
        metadata.put("later", true);

        assertThat(retrieved.meta()).containsEntry("optional", null).doesNotContainKey("later");
        assertThat(retrieved.meta().get("audience"))
                .isEqualTo(Map.of("tags", List.of("registration")));
        assertThatThrownBy(() -> retrieved.meta().put("forbidden", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) retrieved.meta().get("audience")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) ((Map<?, ?>) retrieved.meta().get("audience"))
                .get("tags")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void embeddingFailureUsesGenericMessageWithoutLeakingQuestion(
            CapturedOutput output
    ) {
        String exceptionSecret = "embedding-exception-secret";
        String profileSecret = "embedding-profile-secret";
        RetrievalRequest secretRequest = new RetrievalRequest(
                QUESTION,
                profile(2024, profileSecret, profileSecret),
                new IntentDto(IntentType.OFFICIAL, 0.9),
                2);
        when(embeddingGateway.embed(QUESTION))
                .thenThrow(new IllegalStateException(exceptionSecret));

        assertThatThrownBy(() -> retrievalService.retrieve(secretRequest))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.EMBEDDING_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("Retrieval embedding failed");
                    assertThat(exception.getMessage()).doesNotContain(QUESTION);
                });
        verifyNoInteractions(chunkRepository);
        assertThat(output.getAll())
                .contains("event=rag.retrieval.start intent=OFFICIAL topK=2")
                .containsPattern("event=rag\\.retrieval\\.failure stage=embedding "
                        + "causeType=java\\.lang\\.IllegalStateException elapsedMs=\\d+")
                .doesNotContain(QUESTION, profileSecret, exceptionSecret)
                .doesNotContain("RetrievalServiceTest.java");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void repositoryFailureUsesGenericMessageWithoutLeakingQuestion(
            CapturedOutput output
    ) {
        String exceptionSecret = "search-exception-secret";
        String profileSecret = "search-profile-secret";
        RetrievalRequest secretRequest = new RetrievalRequest(
                QUESTION,
                profile(2024, profileSecret, profileSecret),
                new IntentDto(IntentType.OFFICIAL, 0.9),
                2);
        givenEmbedding();
        when(chunkRepository.search(any()))
                .thenThrow(new IllegalStateException(exceptionSecret));

        assertThatThrownBy(() -> retrievalService.retrieve(secretRequest))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(exception.getMessage()).isEqualTo("Knowledge retrieval failed");
                    assertThat(exception.getMessage()).doesNotContain(QUESTION);
                });
        verify(embeddingGateway).embed(QUESTION);
        assertThat(output.getAll())
                .contains("event=rag.retrieval.start intent=OFFICIAL topK=2")
                .containsPattern("event=rag\\.retrieval\\.failure stage=search "
                        + "causeType=java\\.lang\\.IllegalStateException elapsedMs=\\d+")
                .doesNotContain(QUESTION, profileSecret, exceptionSecret)
                .doesNotContain("RetrievalServiceTest.java");
    }

    @Test
    void retrievalRequestEnforcesApiBoundsAndExplicitNullPolicy() {
        IntentDto intent = new IntentDto(IntentType.UNKNOWN, 0.2);

        assertThatThrownBy(() -> new RetrievalRequest(null, null, intent, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalRequest(" \n", null, intent, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalRequest("x".repeat(1001), null, intent, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalRequest(QUESTION, null, null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("intent");
        assertThatThrownBy(() -> new RetrievalRequest(QUESTION, null, intent, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalRequest(QUESTION, null, intent, 21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retrievalRequestStripsUnicodeWhitespaceBeforeEmbedding() {
        RetrievalRequest request = new RetrievalRequest(
                "\u2003" + QUESTION + "\u2003",
                null,
                new IntentDto(IntentType.UNKNOWN, 0.2),
                1);

        assertThat(request.question()).isEqualTo(QUESTION);
    }

    @Test
    void retrievalRequestCountsQuestionBoundsInUnicodeCodePoints() {
        String emoji = "\uD83D\uDE03";

        RetrievalRequest legal = new RetrievalRequest(
                emoji.repeat(1000), null,
                new IntentDto(IntentType.UNKNOWN, 0.2), 1);

        assertThat(legal.question().codePointCount(0, legal.question().length()))
                .isEqualTo(1000);
        assertThatThrownBy(() -> new RetrievalRequest(
                emoji.repeat(1001), null,
                new IntentDto(IntentType.UNKNOWN, 0.2), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
    }

    private RetrievalRequest request(IntentType type, int topK) {
        return new RetrievalRequest(
                QUESTION, profile(2024, "North Campus", "Engineering"),
                new IntentDto(type, 0.9), topK);
    }

    private UserProfileDto profile(Integer grade, String campus, String college) {
        return new UserProfileDto(
                1L, "Computer Science", grade, campus, Degree.UNDERGRADUATE,
                college, null, List.of(), null);
    }

    private void givenEmbedding() {
        when(embeddingGateway.embed(QUESTION)).thenReturn(queryEmbedding);
    }

    private void givenPools(
            List<EmbeddingChunkSearchResult> official,
            List<EmbeddingChunkSearchResult> ugc
    ) {
        when(chunkRepository.search(any())).thenAnswer(invocation -> {
            EmbeddingChunkSearchCriteria criteria = invocation.getArgument(0);
            return "OFFICIAL".equals(criteria.sourceType()) ? official : ugc;
        });
    }

    private EmbeddingChunkSearchResult result(EmbeddingChunkRecord chunk, double score) {
        if ("OFFICIAL".equals(chunk.sourceType())) {
            return new EmbeddingChunkSearchResult(
                    chunk,
                    score,
                    DocumentReferenceType.EXTERNAL,
                    chunk.docRefId(),
                    null);
        }
        return new EmbeddingChunkSearchResult(chunk, score);
    }

    private EmbeddingChunkRecord chunk(Long id, String sourceType, Map<String, Object> meta) {
        return new EmbeddingChunkRecord(
                id,
                1L,
                sourceType.equals("UGC") ? "UGC" : "DOCUMENT",
                1000L + id,
                0,
                "Chunk " + id,
                unitVector(),
                sourceType,
                sourceType.equals("OFFICIAL") ? "https://example.edu/rules/" + id : null,
                "Title " + id,
                "Source " + id,
                OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                meta,
                OffsetDateTime.parse("2026-07-02T09:00:00+08:00"));
    }

    private RetrievedChunk retrievedForValidation(double score) {
        return new RetrievedChunk(
                1L, "Chunk", "OFFICIAL", score, "https://example.edu/rules",
                "Rules", "Registrar", OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                Map.of());
    }

    private double scoreOf(List<RetrievedChunk> results, Long chunkId) {
        return results.stream()
                .filter(result -> result.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow()
                .score();
    }

    private List<Double> unitVector() {
        List<Double> vector = new ArrayList<>(Collections.nCopies(EMBEDDING_DIMENSIONS, 0.0));
        vector.set(0, 1.0);
        return List.copyOf(vector);
    }
}

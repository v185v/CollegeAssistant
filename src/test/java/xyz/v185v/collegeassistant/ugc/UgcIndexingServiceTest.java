package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;

@ExtendWith(MockitoExtension.class)
class UgcIndexingServiceTest {

    private static final Long POST_ID = 41L;

    @Mock
    private UgcRepository ugcRepository;

    @Mock
    private EmbeddingGateway embeddingGateway;

    @Mock
    private UgcChunkIndexWriter indexWriter;

    private TextChunker chunker;
    private UgcIndexingService indexingService;

    @BeforeEach
    void setUp() {
        chunker = new TextChunker(18, 0);
        indexingService = new UgcIndexingService(
                ugcRepository, chunker, embeddingGateway, indexWriter);
    }

    @Test
    void orchestratorDoesNotHoldADatabaseTransactionAcrossEmbedding() throws Exception {
        Method method = UgcIndexingService.class.getMethod("indexApproved", Long.class);

        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void approvedPostIsChunkedEmbeddedAndReplacedWithCompleteRetrievalMetadata() {
        UgcPostDto approved = post(UgcStatus.APPROVED);
        List<String> texts = List.of("First sentence.", " Second sentence.");
        List<List<Double>> vectors = List.of(vector(1.0), vector(2.0));
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(approved));
        when(embeddingGateway.embedAll(texts)).thenReturn(vectors);

        indexingService.indexApproved(POST_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddingChunkRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(indexWriter).replaceIfStillApproved(eq(POST_ID), eq(1L), records.capture());
        verify(embeddingGateway).embedAll(texts);

        assertThat(records.getValue()).hasSize(2);
        for (int index = 0; index < records.getValue().size(); index++) {
            EmbeddingChunkRecord record = records.getValue().get(index);
            assertThat(record.id()).isNull();
            assertThat(record.embeddingConfigId()).isEqualTo(1L);
            assertThat(record.docRefType()).isEqualTo("UGC");
            assertThat(record.docRefId()).isEqualTo(POST_ID);
            assertThat(record.chunkIndex()).isEqualTo(index);
            assertThat(record.chunkText()).isEqualTo(texts.get(index));
            assertThat(record.embedding()).isEqualTo(vectors.get(index));
            assertThat(record.sourceType()).isEqualTo("UGC");
            assertThat(record.sourceUrl()).isNull();
            assertThat(record.title()).isEqualTo(approved.title());
            assertThat(record.sourceOrg()).isEqualTo(approved.authorName());
            assertThat(record.publishedAt()).isEqualTo(approved.createdAt());
            assertThat(record.degree()).isEqualTo("GENERAL");
            assertThat(record.college()).isEqualTo("GENERAL");
            assertThat(record.campus()).isEqualTo("GENERAL");
            assertThat(record.gradeRange()).isEqualTo("GENERAL");
            assertThat(record.meta()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "authorId", approved.authorId(),
                    "authorName", approved.authorName(),
                    "tags", approved.tags(),
                    "likes", approved.likes(),
                    "reports", approved.reports(),
                    "relatedCourse", approved.relatedCourse(),
                    "relatedLocation", approved.relatedLocation()));
            assertThat(record.createdAt()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(value = UgcStatus.class, names = {"PENDING", "REJECTED", "REMOVED"})
    void nonApprovedPostIsNeverEmbeddedOrIndexed(UgcStatus status) {
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(post(status)));

        indexingService.indexApproved(POST_ID);

        verifyNoInteractions(embeddingGateway, indexWriter);
    }

    @Test
    void missingPostIsNeverEmbeddedOrIndexed() {
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.empty());

        indexingService.indexApproved(POST_ID);

        verifyNoInteractions(embeddingGateway, indexWriter);
    }

    @Test
    void missingOptionalCourseAndLocationAreOmittedFromMetadata() {
        UgcPostDto approved = post(UgcStatus.APPROVED, null, null);
        List<String> texts = List.of("First sentence.", " Second sentence.");
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(approved));
        when(embeddingGateway.embedAll(texts)).thenReturn(List.of(vector(1.0), vector(2.0)));

        indexingService.indexApproved(POST_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddingChunkRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(indexWriter).replaceIfStillApproved(eq(POST_ID), eq(1L), records.capture());
        assertThat(records.getValue())
                .allSatisfy(record -> assertThat(record.meta())
                        .doesNotContainKeys("relatedCourse", "relatedLocation"));
    }

    @Test
    void embeddingProviderFailureBecomesDiagnosableBusinessExceptionWithoutWritingChunks() {
        UgcPostDto approved = post(UgcStatus.APPROVED);
        IllegalStateException providerFailure = new IllegalStateException("provider failed");
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(approved));
        when(embeddingGateway.embedAll(List.of("First sentence.", " Second sentence.")))
                .thenThrow(providerFailure);

        assertThatThrownBy(() -> indexingService.indexApproved(POST_ID))
                .isInstanceOfSatisfying(UgcIndexingException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getStage()).isEqualTo(UgcIndexingStage.EMBEDDING);
                    assertThat(exception.getMessage())
                            .isEqualTo("UGC indexing failed at embedding stage for postId=41");
                    assertThat(exception.getCause()).isSameAs(providerFailure);
                });

        verify(indexWriter, never()).replaceIfStillApproved(
                eq(POST_ID), eq(1L), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void embeddingCountMismatchFailsBeforeAtomicReplacement() {
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(post(UgcStatus.APPROVED)));
        when(embeddingGateway.embedAll(List.of("First sentence.", " Second sentence.")))
                .thenReturn(List.of(vector(1.0)));

        assertThatThrownBy(() -> indexingService.indexApproved(POST_ID))
                .isInstanceOfSatisfying(UgcIndexingException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getStage()).isEqualTo(UgcIndexingStage.EMBEDDING);
                    assertThat(exception.getMessage()).contains("embedding stage");
                });

        verify(indexWriter, never()).replaceIfStillApproved(
                eq(POST_ID), eq(1L), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void atomicReplacementFailureBecomesDiagnosableBusinessException() {
        UgcPostDto approved = post(UgcStatus.APPROVED);
        List<String> texts = List.of("First sentence.", " Second sentence.");
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(approved));
        when(embeddingGateway.embedAll(texts)).thenReturn(List.of(vector(1.0), vector(2.0)));
        IllegalStateException indexFailure = new IllegalStateException("database unavailable");
        when(indexWriter.replaceIfStillApproved(eq(POST_ID),
                eq(1L), org.mockito.ArgumentMatchers.anyList())).thenThrow(indexFailure);

        assertThatThrownBy(() -> indexingService.indexApproved(POST_ID))
                .isInstanceOfSatisfying(UgcIndexingException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getStage()).isEqualTo(UgcIndexingStage.INDEX);
                    assertThat(exception.getMessage())
                            .isEqualTo("UGC indexing failed at index stage for postId=41");
                    assertThat(exception.getCause()).isSameAs(indexFailure);
                });
    }

    @Test
    void repeatedApprovedEventsReplaceTheSameReferenceWithEquivalentChunks() {
        UgcPostDto approved = post(UgcStatus.APPROVED);
        List<String> texts = List.of("First sentence.", " Second sentence.");
        List<List<Double>> vectors = List.of(vector(1.0), vector(2.0));
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(approved));
        when(embeddingGateway.embedAll(texts)).thenReturn(vectors);

        indexingService.indexApproved(POST_ID);
        indexingService.indexApproved(POST_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddingChunkRecord>> replacements = ArgumentCaptor.forClass(List.class);
        verify(indexWriter, org.mockito.Mockito.times(2))
                .replaceIfStillApproved(eq(POST_ID), eq(1L), replacements.capture());
        assertThat(replacements.getAllValues()).hasSize(2);
        assertThat(replacements.getAllValues().get(0))
                .isEqualTo(replacements.getAllValues().get(1));
    }

    @Test
    void embeddingCompletesBeforeTheShortTransactionalWriterRuns() {
        UgcPostDto approved = post(UgcStatus.APPROVED);
        List<String> texts = List.of("First sentence.", " Second sentence.");
        when(ugcRepository.findById(POST_ID)).thenReturn(Optional.of(approved));
        when(embeddingGateway.embedAll(texts)).thenReturn(List.of(vector(1.0), vector(2.0)));

        indexingService.indexApproved(POST_ID);

        InOrder order = inOrder(embeddingGateway, indexWriter);
        order.verify(embeddingGateway).embedAll(texts);
        order.verify(indexWriter).replaceIfStillApproved(
                eq(POST_ID), eq(1L), org.mockito.ArgumentMatchers.anyList());
    }

    private UgcPostDto post(UgcStatus status) {
        return post(status, "CS101", "West Campus");
    }

    private UgcPostDto post(
            UgcStatus status,
            String relatedCourse,
            String relatedLocation
    ) {
        return new UgcPostDto(
                POST_ID,
                8L,
                "Alice",
                "Course selection advice",
                "First sentence. Second sentence.",
                List.of("course", "freshman"),
                relatedCourse,
                relatedLocation,
                status,
                12,
                2,
                status == UgcStatus.REJECTED ? "Rejected" : null,
                OffsetDateTime.parse("2026-07-13T10:15:30+08:00"),
                status == UgcStatus.PENDING
                        ? null
                        : OffsetDateTime.parse("2026-07-13T11:15:30+08:00"));
    }

    private List<Double> vector(double firstCoordinate) {
        List<Double> vector = new ArrayList<>(
                Collections.nCopies(EmbeddingDimensions.DIMENSIONS, 0.0));
        vector.set(0, firstCoordinate);
        return List.copyOf(vector);
    }
}

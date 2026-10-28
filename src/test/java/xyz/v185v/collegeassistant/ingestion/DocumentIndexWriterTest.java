package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.DocumentIndexWriter;
import xyz.v185v.collegeassistant.ingestion.parsing.PreparedEmbeddingChunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;
import xyz.v185v.collegeassistant.ai.EffectiveEmbeddingContext;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentWriteResult;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;

class DocumentIndexWriterTest {

    private DocumentRepository documentRepository;
    private EmbeddingChunkRepository chunkRepository;
    private EmbeddingMaintenanceGuard maintenanceGuard;
    private DocumentIndexWriter writer;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(EmbeddingChunkRepository.class);
        maintenanceGuard = mock(EmbeddingMaintenanceGuard.class);
        writer = new DocumentIndexWriter(documentRepository, chunkRepository, maintenanceGuard);
    }

    @Test
    void addedAndUpdatedDocumentsReplaceChunksUsingReturnedDocumentId() {
        for (DocumentWriteStatus status : List.of(
                DocumentWriteStatus.ADDED, DocumentWriteStatus.UPDATED)) {
            DocumentRecord candidate = candidate();
            DocumentRecord stored = stored(77L);
            when(documentRepository.write(candidate, false))
                    .thenReturn(new DocumentWriteResult(status, stored));

            DocumentWriteResult result = writer.write(
                    candidate, false, List.of(prepared(0)), context());

            assertThat(result.status()).isEqualTo(status);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<EmbeddingChunkRecord>> chunks = ArgumentCaptor.forClass(List.class);
            verify(chunkRepository).replaceForDocument(eq(77L), eq(17L), chunks.capture());
            assertThat(chunks.getValue()).hasSize(1);
            assertThat(chunks.getValue().getFirst().docRefType()).isEqualTo("DOCUMENT");
            assertThat(chunks.getValue().getFirst().docRefId()).isEqualTo(77L);
            assertThat(chunks.getValue().getFirst().embeddingConfigId()).isEqualTo(17L);
            org.mockito.Mockito.reset(documentRepository, chunkRepository);
        }
    }

    @Test
    void unchangedDocumentDoesNotReplaceChunks() {
        DocumentRecord candidate = candidate();
        when(documentRepository.write(candidate, false)).thenReturn(
                new DocumentWriteResult(DocumentWriteStatus.UNCHANGED, stored(77L)));

        DocumentWriteResult result = writer.write(
                candidate, false, List.of(prepared(0)), context());

        assertThat(result.status()).isEqualTo(DocumentWriteStatus.UNCHANGED);
        verify(chunkRepository, never()).replaceForDocument(any(), any(Long.class), any());
    }

    @Test
    void publicTransactionalBoundaryPropagatesChunkFailuresForRollback() throws Exception {
        Method method = DocumentIndexWriter.class.getMethod(
                "write", DocumentRecord.class, boolean.class, List.class,
                EffectiveEmbeddingContext.class);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();

        DocumentRecord candidate = candidate();
        when(documentRepository.write(candidate, true)).thenReturn(
                new DocumentWriteResult(DocumentWriteStatus.UPDATED, stored(77L)));
        IllegalStateException failure = new IllegalStateException("chunk insert failed");
        when(chunkRepository.replaceForDocument(eq(77L), eq(17L), any())).thenThrow(failure);

        assertThatThrownBy(() -> writer.write(candidate, true, List.of(prepared(0)), context()))
                .isSameAs(failure);
    }

    @Test
    void blocksPublicationWhenEmbeddingMaintenanceIsActive() {
        DocumentRecord candidate = candidate();
        when(documentRepository.write(candidate, false)).thenReturn(
                new DocumentWriteResult(DocumentWriteStatus.UNCHANGED, stored(77L)));

        writer.write(candidate, false, List.of(prepared(0)), context());

        verify(maintenanceGuard).requireWritesAllowed();
        verify(documentRepository).write(candidate, false);
    }

    @Test
    void existingDocumentMustStillBeActiveAtTheFinalPublicationBoundary() {
        DocumentRecord candidate = stored(77L);
        when(documentRepository.lockActiveForReindex(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.write(candidate, true, List.of(prepared(0)), context()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("deletion");

        verify(documentRepository, never()).write(any(), any(Boolean.class));
        verify(chunkRepository, never()).replaceForDocument(any(), any(Long.class), any());
    }

    private DocumentRecord candidate() {
        return new DocumentRecord(
                null,
                10L,
                "Calendar",
                "https://example.edu/calendar",
                "Registrar",
                OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                "hash",
                "documents/calendar.html",
                null);
    }

    private DocumentRecord stored(Long id) {
        DocumentRecord candidate = candidate();
        return new DocumentRecord(
                id,
                candidate.sourceId(),
                candidate.title(),
                candidate.sourceUrl(),
                candidate.sourceOrg(),
                candidate.publishedAt(),
                candidate.contentHash(),
                candidate.fileObjectKey(),
                OffsetDateTime.parse("2026-07-14T00:00:00Z"));
    }

    private PreparedEmbeddingChunk prepared(int index) {
        List<Double> vector = new ArrayList<>(
                Collections.nCopies(EmbeddingDimensions.DIMENSIONS, 0.0));
        vector.set(0, 1.0);
        return new PreparedEmbeddingChunk(
                index,
                "chunk " + index,
                vector,
                "OFFICIAL",
                "https://example.edu/calendar",
                "Calendar",
                "Registrar",
                OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                Map.of("author", "Registrar"));
    }

    private EffectiveEmbeddingContext context() {
        return new EffectiveEmbeddingContext(17L, mock(EmbeddingGateway.class));
    }
}

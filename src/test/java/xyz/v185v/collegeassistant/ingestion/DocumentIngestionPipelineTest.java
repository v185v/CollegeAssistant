package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.DocumentIngestionPipeline;
import xyz.v185v.collegeassistant.ingestion.application.DocumentIndexWriter;
import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.LeaseGuardedJobOperations;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.document.DocumentWriteResult;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.storage.StoredObject;

class DocumentIngestionPipelineTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void publishesAnInternalUploadAndCheckpointsItInsideTheLeaseTransaction() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        IngestRunExecutionContext context = mock(IngestRunExecutionContext.class);
        LeaseGuardedIngestOperations operations = mock(LeaseGuardedIngestOperations.class);
        IngestRunLease lease = new IngestRunLease(
                90L, 2, "worker", UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        SourceRecord source = new SourceRecord(
                10L, "Registrar", null, "OFFICIAL", true, NOW, NOW);
        when(context.lease()).thenReturn(lease);
        when(documents.findActiveInternalBySourceIdAndContentHash(eq(10L), any()))
                .thenReturn(Optional.empty());
        when(documents.findActiveInternalBySourceIdAndNormalizedFilename(10L, "handbook.txt"))
                .thenReturn(Optional.empty());
        when(parser.parse(any(), eq("handbook.txt")))
                .thenReturn(new ParsedDocument("Handbook", "campus handbook text", "text/plain", Map.of()));
        when(embeddings.embedAll(any())).thenReturn(List.of(List.of(1.0)));
        when(storage.save(any(), eq("handbook.txt"), any()))
                .thenReturn(new StoredObject("objects/upload", "handbook.txt", 21));
        DocumentRecord stored = new DocumentRecord(
                42L, 10L, "Handbook", "internal://document/12345678-1234-1234-1234-123456789abc",
                "Registrar", NOW, "hash", "objects/upload", NOW,
                DocumentReferenceType.INTERNAL, "https://catalog.example.edu/handbook",
                UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                "handbook.txt", "handbook.txt", DocumentStatus.ACTIVE,
                null, null, NOW, null, "GENERAL", "GENERAL", "GENERAL", "GENERAL");
        when(indexWriter.write(any(), eq(false), any(), any()))
                .thenReturn(new DocumentWriteResult(DocumentWriteStatus.ADDED, stored));
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedIngestOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });

        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);
        DocumentIngestionPipeline.PipelineResult result = pipeline.ingest(
                request(source), context, 19L);

        assertThat(result.status()).isEqualTo(DocumentWriteStatus.ADDED);
        verify(indexWriter).write(any(DocumentRecord.class), eq(false), any(), any());
        verify(operations).checkpoint(eq(19L), eq(IngestRunItemStatus.SUCCEEDED), eq(42L), any());
        verify(storage).save(
                org.mockito.ArgumentMatchers.contains("run-90-item-19-attempt-2-lease-"),
                eq("handbook.txt"), any());
    }

    @Test
    void skipsSameHashWithoutParsingOrEmbedding() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        SourceRecord source = new SourceRecord(
                10L, "Registrar", null, "OFFICIAL", true, NOW, NOW);
        DocumentRecord existing = new DocumentRecord(
                40L, 10L, "Existing", "internal://document/12345678-1234-1234-1234-123456789abc",
                "Registrar", NOW,
                "a795609d92eb9ad5381b4d31d0efb385856a0ba31d172e3836dd4790b60ad10d",
                "objects/old", NOW, DocumentReferenceType.INTERNAL, null,
                UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                "handbook.txt", "handbook.txt", DocumentStatus.ACTIVE,
                null, null, NOW, null, "GENERAL", "GENERAL", "GENERAL", "GENERAL");
        when(documents.findActiveInternalBySourceIdAndContentHash(10L, existing.contentHash()))
                .thenReturn(Optional.of(existing));

        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);

        DocumentIngestionPipeline.PipelineResult result = pipeline.ingest(
                request(source), null, null);

        assertThat(result.status()).isEqualTo(DocumentWriteStatus.UNCHANGED);
        assertThat(result.document()).isEqualTo(existing);
        verify(parser, never()).parse(any(), any());
        verify(embeddings, never()).embedAll(any());
    }

    @Test
    void unchangedExternalBodyRefreshesValidatorsWithoutReindexing() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        String url = "https://catalog.example.edu/handbook.html";
        byte[] body = "campus handbook text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = "a795609d92eb9ad5381b4d31d0efb385856a0ba31d172e3836dd4790b60ad10d";
        SourceRecord source = new SourceRecord(
                11L, "Catalog", "https://catalog.example.edu", "OFFICIAL", true, NOW, NOW);
        DocumentRecord existing = new DocumentRecord(
                41L, 11L, "Existing external", url, "Catalog", NOW,
                hash, "objects/external-old", NOW, DocumentReferenceType.EXTERNAL, url, null,
                null, null, DocumentStatus.ACTIVE, "\"old\"", NOW.minusDays(1), NOW, NOW,
                "GENERAL", "GENERAL", "GENERAL", "GENERAL");
        when(documents.findBySourceIdAndSourceUrl(11L, url)).thenReturn(Optional.of(existing));
        when(documents.write(any(DocumentRecord.class), eq(true))).thenAnswer(invocation ->
                new DocumentWriteResult(DocumentWriteStatus.UPDATED, invocation.getArgument(0)));
        OffsetDateTime checkedAt = NOW.plusHours(1);
        OffsetDateTime modifiedAt = NOW.minusHours(2);
        DocumentIngestionPipeline.PipelineRequest request =
                new DocumentIngestionPipeline.PipelineRequest(
                        source, body, "handbook.html", null, url, modifiedAt,
                        DocumentReferenceType.EXTERNAL, null,
                        null, null, null, null, true, false,
                        "\"new\"", modifiedAt, checkedAt);
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);

        DocumentIngestionPipeline.PipelineResult result = pipeline.ingest(request, null, null);

        assertThat(result.status()).isEqualTo(DocumentWriteStatus.UNCHANGED);
        ArgumentCaptor<DocumentRecord> refreshed = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documents).write(refreshed.capture(), eq(true));
        assertThat(refreshed.getValue().etag()).isEqualTo("\"new\"");
        assertThat(refreshed.getValue().httpLastModified()).isEqualTo(modifiedAt);
        assertThat(refreshed.getValue().lastCheckedAt()).isEqualTo(checkedAt);
        assertThat(refreshed.getValue().contentHash()).isEqualTo(hash);
        verify(parser, never()).parse(any(), any());
        verify(embeddings, never()).embedAll(any());
        verify(storage, never()).save(any(), any(), any());
        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminReindexReusesTheObjectAndPublishesUnderSourceAndDocumentLocks() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        SourceRecord source = new SourceRecord(
                10L, "Registrar", null, "OFFICIAL", true, NOW, NOW);
        UUID locator = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        DocumentRecord existing = new DocumentRecord(
                40L, 10L, "Existing", "internal://document/" + locator,
                "Registrar", NOW,
                "a795609d92eb9ad5381b4d31d0efb385856a0ba31d172e3836dd4790b60ad10d",
                "objects/old", NOW, DocumentReferenceType.INTERNAL,
                "https://catalog.example.edu/handbook", locator,
                "handbook.txt", "handbook.txt", DocumentStatus.ACTIVE,
                null, null, NOW, null, "GENERAL", "GENERAL", "GENERAL", "GENERAL");
        when(documents.findActiveInternalBySourceIdAndContentHash(10L, existing.contentHash()))
                .thenReturn(Optional.of(existing));
        when(parser.parse(any(), eq("handbook.txt")))
                .thenReturn(new ParsedDocument("Handbook", "campus handbook text", "text/plain", Map.of()));
        when(embeddings.embedAll(any())).thenReturn(List.of(List.of(1.0)));
        when(documents.lockActiveForReindex(40L)).thenReturn(Optional.of(existing));
        when(indexWriter.write(any(), eq(true), any(), any()))
                .thenReturn(new DocumentWriteResult(DocumentWriteStatus.UPDATED, existing));
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);
        DocumentIngestionPipeline.PipelineRequest reindex =
                new DocumentIngestionPipeline.PipelineRequest(
                        source, "campus handbook text".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "handbook.txt", existing.title(), existing.externalSourceUrl(), NOW,
                        DocumentReferenceType.INTERNAL, 40L, "GENERAL", "GENERAL", "GENERAL",
                        "GENERAL", true, true);

        DocumentIngestionPipeline.PipelineResult result =
                pipeline.ingestForAdminJob(reindex, context, 90L);

        ArgumentCaptor<DocumentRecord> candidate = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(indexWriter).write(candidate.capture(), eq(true), any(), any());
        assertThat(result.document().id()).isEqualTo(40L);
        assertThat(candidate.getValue().id()).isEqualTo(40L);
        assertThat(candidate.getValue().internalLocator()).isEqualTo(locator);
        assertThat(candidate.getValue().fileObjectKey()).isEqualTo("objects/old");
        verify(storage, never()).save(any(), any(), any());
        verify(documents).lockActiveForReindex(40L);
        verify(operations).checkpoint(eq(90L), eq(AdminJobStatus.RUNNING), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminReindexAlsoPreservesExternalDocumentIdentity() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        String url = "https://catalog.example.edu/handbook";
        SourceRecord source = new SourceRecord(
                11L, "Catalog", "https://catalog.example.edu", "OFFICIAL", true, NOW, NOW);
        DocumentRecord existing = new DocumentRecord(
                41L, 11L, "Existing external", url, "Catalog", NOW,
                "a795609d92eb9ad5381b4d31d0efb385856a0ba31d172e3836dd4790b60ad10d",
                "objects/external-old", NOW);
        when(documents.findBySourceIdAndSourceUrl(11L, url)).thenReturn(Optional.of(existing));
        when(parser.parse(any(), eq("handbook.html")))
                .thenReturn(new ParsedDocument(
                        "Handbook", "campus handbook text", "text/html", Map.of()));
        when(embeddings.embedAll(any())).thenReturn(List.of(List.of(1.0)));
        when(documents.lockActiveForReindex(41L)).thenReturn(Optional.of(existing));
        when(indexWriter.write(any(), eq(true), any(), any()))
                .thenReturn(new DocumentWriteResult(DocumentWriteStatus.UPDATED, existing));
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);
        DocumentIngestionPipeline.PipelineRequest reindex =
                new DocumentIngestionPipeline.PipelineRequest(
                        source, "campus handbook text".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "handbook.html", existing.title(), url, NOW,
                        DocumentReferenceType.EXTERNAL, 41L, "GENERAL", "GENERAL", "GENERAL",
                        "GENERAL", true, true);

        pipeline.ingestForAdminJob(reindex, context, 91L);

        ArgumentCaptor<DocumentRecord> candidate = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(indexWriter).write(candidate.capture(), eq(true), any(), any());
        assertThat(candidate.getValue().id()).isEqualTo(41L);
        assertThat(candidate.getValue().sourceUrl()).isEqualTo(url);
        assertThat(candidate.getValue().fileObjectKey()).isEqualTo("objects/external-old");
        verify(storage, never()).save(any(), any(), any());
        verify(documents).lockActiveForReindex(41L);
    }

    @Test
    void deletingExternalDocumentIsRejectedBeforeParsing() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        String url = "https://catalog.example.edu/handbook";
        SourceRecord source = new SourceRecord(
                11L, "Catalog", "https://catalog.example.edu", "OFFICIAL", true, NOW, NOW);
        DocumentRecord deleting = new DocumentRecord(
                41L, 11L, "Deleting external", url, "Catalog", NOW,
                "a795609d92eb9ad5381b4d31d0efb385856a0ba31d172e3836dd4790b60ad10d",
                "objects/external-old", NOW, DocumentReferenceType.EXTERNAL, url, null,
                null, null, DocumentStatus.DELETING, null, null, NOW, NOW,
                "GENERAL", "GENERAL", "GENERAL", "GENERAL");
        when(documents.findBySourceIdAndSourceUrl(11L, url)).thenReturn(Optional.of(deleting));
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);
        DocumentIngestionPipeline.PipelineRequest request =
                new DocumentIngestionPipeline.PipelineRequest(
                        source, "campus handbook text".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "handbook.html", deleting.title(), url, NOW,
                        DocumentReferenceType.EXTERNAL, null, "GENERAL", "GENERAL", "GENERAL",
                        "GENERAL", false, false);

        assertThatThrownBy(() -> pipeline.ingest(request, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("delet");
        verify(parser, never()).parse(any(), any());
        verify(indexWriter, never()).write(any(), any(Boolean.class), any(), any());
    }

    @Test
    void deletingInternalIdentityCannotBeRecreatedAsANewUpload() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        SourceRecord source = new SourceRecord(
                10L, "Registrar", null, "OFFICIAL", true, NOW, NOW);
        UUID locator = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        DocumentRecord deleting = new DocumentRecord(
                40L, 10L, "Deleting internal", "internal://document/" + locator,
                "Registrar", NOW,
                "a795609d92eb9ad5381b4d31d0efb385856a0ba31d172e3836dd4790b60ad10d",
                "objects/old", NOW, DocumentReferenceType.INTERNAL, null, locator,
                "handbook.txt", "handbook.txt", DocumentStatus.DELETING,
                null, null, NOW, NOW, "GENERAL", "GENERAL", "GENERAL", "GENERAL");
        when(documents.findInternalBySourceIdAndContentHash(10L, deleting.contentHash()))
                .thenReturn(Optional.of(deleting));
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);

        assertThatThrownBy(() -> pipeline.ingest(request(source), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("delet");

        verify(parser, never()).parse(any(), any());
        verify(documents, never()).findActiveInternalBySourceIdAndContentHash(any(), any());
    }

    @Test
    void deletingSourceSnapshotIsRejectedBeforeAnyDocumentLookup() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        SourceRecord deletingSource = new SourceRecord(
                11L, "Catalog", "https://catalog.example.edu", "OFFICIAL", false,
                SourceConnectorType.SINGLE_URL, "DELETING", SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 3, 500, 4, List.of(), List.of(),
                null, null, 1L, NOW, NOW);
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
                documents, parser, new TextChunker(), embeddings, storage, indexWriter);
        DocumentIngestionPipeline.PipelineRequest request =
                new DocumentIngestionPipeline.PipelineRequest(
                        deletingSource, "campus handbook text".getBytes(), "handbook.html",
                        "Handbook", "https://catalog.example.edu/handbook", NOW,
                        DocumentReferenceType.EXTERNAL, null, "GENERAL", "GENERAL", "GENERAL",
                        "GENERAL", false, false);

        assertThatThrownBy(() -> pipeline.ingest(request, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("source");
        verify(documents, never()).findBySourceIdAndSourceUrl(any(), any());
    }

    private DocumentIngestionPipeline.PipelineRequest request(SourceRecord source) {
        return new DocumentIngestionPipeline.PipelineRequest(
                source, "campus handbook text".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "handbook.txt", "Handbook", "https://catalog.example.edu/handbook",
                NOW, DocumentReferenceType.INTERNAL, null,
                "GENERAL", "GENERAL", "GENERAL", "GENERAL", false, false);
    }
}

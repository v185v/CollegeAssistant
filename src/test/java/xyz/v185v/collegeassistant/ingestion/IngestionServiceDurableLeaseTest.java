package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.DocumentIndexWriter;
import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;
import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentWriteResult;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.storage.StoredObject;

class IngestionServiceDurableLeaseTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());

    @Test
    @SuppressWarnings("unchecked")
    void productionConstructorPublishesInsideAttemptTokenGuard() {
        SourceRepository sourceRepository = mock(SourceRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        IngestRunRepository runRepository = mock(IngestRunRepository.class);
        SourceCrawler crawler = mock(SourceCrawler.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        IngestRunItemRepository itemRepository = mock(IngestRunItemRepository.class);
        IngestRunLeaseGuard guard = mock(IngestRunLeaseGuard.class);
        IngestRunLeaseHeartbeatScheduler heartbeatScheduler =
                mock(IngestRunLeaseHeartbeatScheduler.class);
        IngestRunLease lease = new IngestRunLease(
                90L, 0, "worker-a", UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        IngestRunDto running = new IngestRunDto(
                90L, 10L, IngestRunStatus.RUNNING, 0, 0, 0, 0, null, NOW, null);
        IngestRunDto succeeded = new IngestRunDto(
                90L, 10L, IngestRunStatus.SUCCESS, 1, 0, 0, 0, null, NOW, NOW);
        SourceRecord source = new SourceRecord(
                10L, "Registrar", "https://example.edu", "OFFICIAL", true, NOW, NOW);
        IngestRunItemDto item = new IngestRunItemDto(
                19L, 90L, "https://example.edu/plan.html", "URL",
                IngestRunItemStatus.QUEUED, null, 0, null, null, null, Map.of(), 0, NOW, NOW);
        LeaseGuardedIngestOperations guardedOperations = mock(LeaseGuardedIngestOperations.class);

        when(sourceRepository.findById(10L)).thenReturn(Optional.of(source));
        when(runRepository.startLease(eq(10L), eq(null), eq("MANUAL"), anyString()))
                .thenReturn(lease);
        when(runRepository.findById(90L)).thenReturn(Optional.of(running), Optional.of(succeeded));
        when(crawler.crawl(eq(source), any(CrawlContext.class), eq(false))).thenReturn(List.of(new CrawledDocument(
                "https://example.edu/plan.html", "plan.html", "text/html", NOW,
                "plan content".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        when(documentRepository.findBySourceIdAndSourceUrl(10L, "https://example.edu/plan.html"))
                .thenReturn(Optional.empty());
        when(parser.parse(any(ByteArrayInputStream.class), eq("plan.html")))
                .thenReturn(new ParsedDocument("Plan", "plan body", "text/html", Map.of()));
        when(embeddings.embedAll(any())).thenReturn(List.of(List.of(1.0)));
        when(itemRepository.upsertItem(lease, "https://example.edu/plan.html", "URL"))
                .thenReturn(Optional.of(item));
        when(storage.save(any(), eq("plan.html"), any(ByteArrayInputStream.class)))
                .thenReturn(new StoredObject("immutable/key", "plan.html", 12));
        DocumentRecord document = new DocumentRecord(
                42L, 10L, "Plan", "https://example.edu/plan.html", "Registrar", NOW,
                "hash", "immutable/key", NOW);
        when(indexWriter.write(any(DocumentRecord.class), eq(false), any(), any()))
                .thenReturn(new DocumentWriteResult(DocumentWriteStatus.ADDED, document));
        when(runRepository.finish(
                eq(lease), eq(IngestRunStatus.SUCCESS), eq(1L), eq(0L), eq(0L), eq(0L),
                eq(null), eq(null))).thenReturn(true);
        when(guard.inTransaction(eq(lease), any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedIngestOperations, Object> work = invocation.getArgument(1);
            return work.apply(guardedOperations);
        });

        IngestionService service = new IngestionService(
                sourceRepository, documentRepository, runRepository, crawler, parser,
                new TextChunker(), embeddings, storage, indexWriter, itemRepository, guard,
                heartbeatScheduler, Runnable::run);

        IngestRunDto result = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.SUCCESS);
        verify(heartbeatScheduler).register(lease);
        verify(heartbeatScheduler).unregister(lease);
        verify(guard).inTransaction(eq(lease), any(Function.class));
        verify(guardedOperations).checkpoint(
                eq(19L), eq(IngestRunItemStatus.SUCCEEDED), eq(42L), any());
        ArgumentCaptor<String> namespace = ArgumentCaptor.forClass(String.class);
        verify(storage).save(namespace.capture(), eq("plan.html"), any(ByteArrayInputStream.class));
        assertThat(namespace.getValue()).contains(
                "run-90-item-19-attempt-0-lease-", lease.leaseToken().toString());
        assertThat(namespace.getValue()).doesNotContain("/");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedDocumentGetsADurableFailedCheckpoint() {
        SourceRepository sourceRepository = mock(SourceRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        IngestRunRepository runRepository = mock(IngestRunRepository.class);
        SourceCrawler crawler = mock(SourceCrawler.class);
        DocumentParser parser = mock(DocumentParser.class);
        IngestRunItemRepository itemRepository = mock(IngestRunItemRepository.class);
        IngestRunLeaseGuard guard = mock(IngestRunLeaseGuard.class);
        IngestRunLeaseHeartbeatScheduler heartbeatScheduler =
                mock(IngestRunLeaseHeartbeatScheduler.class);
        IngestRunLease lease = new IngestRunLease(
                91L, 0, "worker-a", UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        IngestRunDto running = new IngestRunDto(
                91L, 10L, IngestRunStatus.RUNNING, 0, 0, 0, 0, null, NOW, null);
        IngestRunDto failed = new IngestRunDto(
                91L, 10L, IngestRunStatus.FAILED, 0, 0, 0, 1, "parse failed", NOW, NOW);
        SourceRecord source = new SourceRecord(
                10L, "Registrar", "https://example.edu", "OFFICIAL", true, NOW, NOW);
        IngestRunItemDto item = new IngestRunItemDto(
                20L, 91L, "https://example.edu/bad.html", "URL",
                IngestRunItemStatus.QUEUED, null, 0, null, null, null, Map.of(), 0, NOW, NOW);
        LeaseGuardedIngestOperations guardedOperations = mock(LeaseGuardedIngestOperations.class);

        when(sourceRepository.findById(10L)).thenReturn(Optional.of(source));
        when(runRepository.startLease(eq(10L), eq(null), eq("MANUAL"), anyString()))
                .thenReturn(lease);
        when(runRepository.findById(91L)).thenReturn(Optional.of(running), Optional.of(failed));
        when(crawler.crawl(eq(source), any(CrawlContext.class), eq(false))).thenReturn(List.of(new CrawledDocument(
                "https://example.edu/bad.html", "bad.html", "text/html", NOW,
                "bad content".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        when(documentRepository.findBySourceIdAndSourceUrl(10L, "https://example.edu/bad.html"))
                .thenReturn(Optional.empty());
        when(itemRepository.upsertItem(lease, "https://example.edu/bad.html", "URL"))
                .thenReturn(Optional.of(item));
        when(parser.parse(any(ByteArrayInputStream.class), eq("bad.html")))
                .thenThrow(new IllegalStateException("parser internals"));
        when(runRepository.finish(
                eq(lease), eq(IngestRunStatus.FAILED), eq(0L), eq(0L), eq(0L), eq(1L),
                eq(null), anyString())).thenReturn(true);
        when(guard.inTransaction(eq(lease), any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedIngestOperations, Object> work = invocation.getArgument(1);
            return work.apply(guardedOperations);
        });

        IngestionService service = new IngestionService(
                sourceRepository, documentRepository, runRepository, crawler, parser,
                new TextChunker(), mock(EmbeddingGateway.class), mock(ObjectStorage.class),
                mock(DocumentIndexWriter.class), itemRepository, guard, heartbeatScheduler, Runnable::run);

        IngestRunDto result = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.FAILED);
        verify(guardedOperations).checkpoint(
                eq(20L), eq(IngestRunItemStatus.FAILED), eq(null),
                argThat(checkpoint -> checkpoint.equals(Map.of("stage", "parse"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryProcessesOnlyThePersistedFailedItems() {
        SourceRepository sourceRepository = mock(SourceRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        IngestRunRepository runRepository = mock(IngestRunRepository.class);
        SourceCrawler crawler = mock(SourceCrawler.class);
        DocumentParser parser = mock(DocumentParser.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIndexWriter indexWriter = mock(DocumentIndexWriter.class);
        IngestRunItemRepository itemRepository = mock(IngestRunItemRepository.class);
        IngestRunLeaseGuard guard = mock(IngestRunLeaseGuard.class);
        IngestRunLeaseHeartbeatScheduler heartbeatScheduler =
                mock(IngestRunLeaseHeartbeatScheduler.class);
        IngestRunLease lease = new IngestRunLease(
                92L, 1, "worker-a", UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        IngestRunDto oldFailed = new IngestRunDto(
                40L, 10L, IngestRunStatus.FAILED, 0, 0, 0, 1, "failed", NOW, NOW);
        IngestRunDto running = new IngestRunDto(
                92L, 10L, IngestRunStatus.RUNNING, 0, 0, 0, 0, null, NOW, null);
        IngestRunDto succeeded = new IngestRunDto(
                92L, 10L, IngestRunStatus.SUCCESS, 1, 0, 0, 0, null, NOW, NOW);
        SourceRecord source = new SourceRecord(
                10L, "Registrar", "https://example.edu", "OFFICIAL", true, NOW, NOW);
        IngestRunItemDto retryItem = new IngestRunItemDto(
                21L, 92L, "https://example.edu/retry.html", "URL",
                IngestRunItemStatus.QUEUED, null, 1, null, null, null, Map.of(), 0, NOW, NOW);
        LeaseGuardedIngestOperations guardedOperations = mock(LeaseGuardedIngestOperations.class);

        when(runRepository.findById(40L)).thenReturn(Optional.of(oldFailed));
        when(sourceRepository.findById(10L)).thenReturn(Optional.of(source));
        when(runRepository.retryFailed(eq(40L), anyString())).thenReturn(lease);
        when(runRepository.findById(92L)).thenReturn(Optional.of(running), Optional.of(succeeded));
        when(itemRepository.findByRunId(92L)).thenReturn(List.of(retryItem));
        when(crawler.crawl(eq(source), any(CrawlContext.class), eq(false))).thenReturn(List.of(
                new CrawledDocument("https://example.edu/unrelated.html", "unrelated.html", "text/html", NOW,
                        "unrelated".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new CrawledDocument("https://example.edu/retry.html", "retry.html", "text/html", NOW,
                        "retry content".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        when(documentRepository.findBySourceIdAndSourceUrl(10L, "https://example.edu/retry.html"))
                .thenReturn(Optional.empty());
        when(itemRepository.upsertItem(lease, "https://example.edu/retry.html", "URL"))
                .thenReturn(Optional.of(retryItem));
        when(parser.parse(any(ByteArrayInputStream.class), eq("retry.html")))
                .thenReturn(new ParsedDocument("Retry", "retry body", "text/html", Map.of()));
        when(embeddings.embedAll(any())).thenReturn(List.of(List.of(1.0)));
        when(storage.save(any(), eq("retry.html"), any(ByteArrayInputStream.class)))
                .thenReturn(new StoredObject("immutable/retry", "retry.html", 12));
        DocumentRecord document = new DocumentRecord(
                43L, 10L, "Retry", "https://example.edu/retry.html", "Registrar", NOW,
                "hash", "immutable/retry", NOW);
        when(indexWriter.write(any(DocumentRecord.class), eq(false), any(), any()))
                .thenReturn(new DocumentWriteResult(DocumentWriteStatus.ADDED, document));
        when(runRepository.finish(
                eq(lease), eq(IngestRunStatus.SUCCESS), eq(1L), eq(0L), eq(0L), eq(0L),
                eq(null), eq(null))).thenReturn(true);
        when(guard.inTransaction(eq(lease), any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedIngestOperations, Object> work = invocation.getArgument(1);
            return work.apply(guardedOperations);
        });

        IngestionService service = new IngestionService(
                sourceRepository, documentRepository, runRepository, crawler, parser,
                new TextChunker(), embeddings, storage, indexWriter, itemRepository, guard,
                heartbeatScheduler, Runnable::run);

        service.retryFailedRun(ADMIN, 40L);

        verify(parser).parse(any(ByteArrayInputStream.class), eq("retry.html"));
        verify(parser, never()).parse(any(ByteArrayInputStream.class), eq("unrelated.html"));
        verify(itemRepository, never()).upsertItem(lease, "https://example.edu/unrelated.html", "URL");
    }
}

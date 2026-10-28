package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.DocumentIndexWriter;
import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;
import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.PreparedEmbeddingChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentWriteResult;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.storage.StoredObject;
import xyz.v185v.collegeassistant.storage.StoredObjectMetadata;

class IngestionServiceTest {

    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(7L, "student", UserRole.STUDENT.name());
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-14T09:00:00Z");

    private SourceRepository sourceRepository;
    private DocumentRepository documentRepository;
    private IngestRunRepository runRepository;
    private DocumentIndexWriter indexWriter;
    private FakeCrawler crawler;
    private FakeParser parser;
    private FakeEmbeddingGateway embeddingGateway;
    private FakeStorage storage;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(SourceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        runRepository = mock(IngestRunRepository.class);
        indexWriter = mock(DocumentIndexWriter.class);
        crawler = new FakeCrawler();
        parser = new FakeParser();
        embeddingGateway = new FakeEmbeddingGateway();
        storage = new FakeStorage();

        when(sourceRepository.findById(10L)).thenReturn(Optional.of(source(true)));
        when(runRepository.start(anyLong(), isNull())).thenReturn(runningRun());
        when(runRepository.start(anyLong(), anyString())).thenReturn(runningRun());
        when(runRepository.heartbeat(anyLong())).thenReturn(true);
        when(runRepository.finish(
                anyLong(), any(IngestRunStatus.class), anyLong(), anyLong(),
                anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> new IngestRunDto(
                        invocation.getArgument(0),
                        10L,
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5),
                        invocation.getArgument(6),
                        NOW,
                        NOW.plusMinutes(1)));
        when(documentRepository.findBySourceIdAndSourceUrl(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(indexWriter.write(any(DocumentRecord.class), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> {
                    DocumentRecord candidate = invocation.getArgument(0);
                    return new DocumentWriteResult(
                            DocumentWriteStatus.ADDED,
                            storedDocument(100L, candidate));
                });

        service = new IngestionService(
                sourceRepository,
                documentRepository,
                runRepository,
                crawler,
                parser,
                new TextChunker(),
                embeddingGateway,
                storage,
                indexWriter,
                Runnable::run);
    }

    @Test
    void serviceRejectsNonAdminsForRunAndHistory() {
        assertAdminFailure(() -> service.run(STUDENT, 10L, new RunIngestRequest(false, false, null)));
        assertAdminFailure(() -> service.listRuns(STUDENT, null, null, 1, 20));

        verify(runRepository, never()).start(anyLong(), any());
        verify(runRepository, never()).find(any(), any(), anyInt(), anyInt());
    }

    @Test
    void missingOrDisabledSourcesFailBeforeStartingARun() {
        when(sourceRepository.findById(10L)).thenReturn(Optional.empty());
        assertSourceFailure(() -> service.run(ADMIN, 10L, new RunIngestRequest(false, false, null)));

        when(sourceRepository.findById(10L)).thenReturn(Optional.of(source(false)));
        assertSourceFailure(() -> service.run(ADMIN, 10L, new RunIngestRequest(false, false, null)));

        verify(runRepository, never()).start(anyLong(), any());
    }

    @Test
    void unchangedHashSkipsBeforeParsingEmbeddingOrStorage() {
        CrawledDocument crawled = crawled("same.html", "same-content");
        crawler.documents = List.of(crawled);
        when(documentRepository.findBySourceIdAndSourceUrl(10L, crawled.sourceUrl()))
                .thenReturn(Optional.of(existing(crawled.sourceUrl(), sha256(crawled.rawBytes()), "old-key")));

        IngestRunDto result = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.SUCCESS);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.added()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(parser.calls).isZero();
        assertThat(embeddingGateway.calls).isZero();
        assertThat(storage.savedKeys).isEmpty();
        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
    }

    @Test
    void notModifiedRefreshesValidatorsWithoutOpeningTheParsingPipeline() {
        String url = "https://example.edu/not-modified.html";
        OffsetDateTime lastModified = NOW.minusHours(2);
        crawler.documents = List.of(CrawledDocument.notModified(
                url, "not-modified.html", "text/html", "\"new-tag\"", lastModified));
        when(documentRepository.findBySourceIdAndSourceUrl(10L, url))
                .thenReturn(Optional.of(existing(url, "a".repeat(64), "documents/existing.html")));

        IngestRunDto result = service.run(
                ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.SUCCESS);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(parser.calls).isZero();
        assertThat(embeddingGateway.calls).isZero();
        assertThat(storage.savedKeys).isEmpty();
        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
        ArgumentCaptor<DocumentRecord> refreshed = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRepository).write(refreshed.capture(),
                org.mockito.ArgumentMatchers.eq(true));
        assertThat(refreshed.getValue().etag()).isEqualTo("\"new-tag\"");
        assertThat(refreshed.getValue().httpLastModified()).isEqualTo(lastModified);
        assertThat(refreshed.getValue().lastCheckedAt()).isAfterOrEqualTo(NOW);
    }

    @Test
    void addedDocumentStoresParsesEmbedsAndIndexesCompleteMetadata() {
        CrawledDocument crawled = crawled("calendar.html", "calendar content");
        crawler.documents = List.of(crawled);

        IngestRunDto result = service.run(ADMIN, 10L, new RunIngestRequest(false, false, "manual"));

        assertThat(result.status()).isEqualTo(IngestRunStatus.SUCCESS);
        assertThat(result.added()).isEqualTo(1);
        assertThat(parser.calls).isEqualTo(1);
        assertThat(embeddingGateway.calls).isEqualTo(1);
        assertThat(storage.savedKeys).hasSize(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PreparedEmbeddingChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<DocumentRecord> documentCaptor = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(indexWriter).write(
                documentCaptor.capture(), org.mockito.ArgumentMatchers.eq(false),
                chunksCaptor.capture(), any());
        DocumentRecord candidate = documentCaptor.getValue();
        assertThat(candidate.contentHash()).isEqualTo(sha256(crawled.rawBytes()));
        assertThat(candidate.fileObjectKey()).startsWith("documents/");
        assertThat(candidate.sourceOrg()).isEqualTo("Registrar");
        PreparedEmbeddingChunk chunk = chunksCaptor.getValue().getFirst();
        assertThat(chunk.sourceType()).isEqualTo("OFFICIAL");
        assertThat(chunk.sourceUrl()).isEqualTo(crawled.sourceUrl());
        assertThat(chunk.sourceOrg()).isEqualTo("Registrar");
        assertThat(chunk.publishedAt()).isEqualTo(crawled.lastModified());
        assertThat(chunk.degree()).isEqualTo("GENERAL");
        assertThat(chunk.college()).isEqualTo("GENERAL");
        assertThat(chunk.campus()).isEqualTo("GENERAL");
        assertThat(chunk.gradeRange()).isEqualTo("GENERAL");
        assertThat(chunk.meta()).containsEntry("author", "Registrar");
        verify(runRepository, atLeast(2)).heartbeat(900L);
    }

    @Test
    void lostLeaseAfterCrawlStopsBeforeIndexOrFinish() {
        crawler.documents = List.of(crawled("lease-lost.html", "content"));
        when(runRepository.heartbeat(900L)).thenReturn(true, false);

        assertThatThrownBy(() -> service.run(
                        ADMIN, 10L, new RunIngestRequest(false, false, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getMessage()).isEqualTo(
                            "Ingestion lease is no longer active");
                });

        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void asyncWorkerStopsAfterFirstDocumentWhenLeaseIsLost() {
        ControllableExecutor executor = new ControllableExecutor();
        IngestionService asyncService = serviceWithExecutor(executor);
        crawler.documents = List.of(
                crawled("first.html", "first"),
                crawled("second.html", "second"));
        when(runRepository.heartbeat(900L)).thenReturn(true, true, true, true, false);

        asyncService.start(ADMIN, 10L, new RunIngestRequest(false, false, null));
        executor.runNext();

        verify(indexWriter, times(1)).write(any(), anyBoolean(), any(), any());
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void asyncWorkerStopsBeforeStorageWhenLeaseIsLostDuringPreparation() {
        ControllableExecutor executor = new ControllableExecutor();
        IngestionService asyncService = serviceWithExecutor(executor);
        crawler.documents = List.of(crawled("prepared.html", "content"));
        when(runRepository.heartbeat(900L)).thenReturn(true, true, false);

        asyncService.start(ADMIN, 10L, new RunIngestRequest(false, false, null));
        executor.runNext();

        assertThat(storage.savedKeys).isEmpty();
        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void asyncWorkerCompensatesStoredObjectWhenLeaseIsLostBeforeIndex() {
        ControllableExecutor executor = new ControllableExecutor();
        IngestionService asyncService = serviceWithExecutor(executor);
        crawler.documents = List.of(crawled("stored.html", "content"));
        when(runRepository.heartbeat(900L)).thenReturn(true, true, true, false);

        asyncService.start(ADMIN, 10L, new RunIngestRequest(false, false, null));
        executor.runNext();

        assertThat(storage.savedKeys).hasSize(1);
        assertThat(storage.deletedKeys).containsExactlyElementsOf(storage.savedKeys);
        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void reclaimedQueuedWorkerStopsBeforeCrawl() {
        ControllableExecutor executor = new ControllableExecutor();
        IngestionService asyncService = serviceWithExecutor(executor);
        crawler.documents = List.of(crawled("queued.html", "content"));
        when(runRepository.heartbeat(900L)).thenReturn(false);

        asyncService.start(ADMIN, 10L, new RunIngestRequest(false, false, null));
        executor.runNext();

        assertThat(crawler.calls).isZero();
        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void workerDoesNotFinishWhenLeaseIsLostDuringFailedCrawl() {
        ControllableExecutor executor = new ControllableExecutor();
        IngestionService asyncService = serviceWithExecutor(executor);
        crawler.failure = new IllegalStateException("SECRET crawl detail");
        when(runRepository.heartbeat(900L)).thenReturn(true, false);

        asyncService.start(ADMIN, 10L, new RunIngestRequest(false, false, null));
        executor.runNext();

        assertThat(crawler.calls).isEqualTo(1);
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void changedDocumentCountsUpdatedAndDeletesOldObjectAfterSuccess() {
        CrawledDocument crawled = crawled("changed.html", "new content");
        crawler.documents = List.of(crawled);
        DocumentRecord old = existing(crawled.sourceUrl(), "old-hash", "documents/old.html");
        when(documentRepository.findBySourceIdAndSourceUrl(10L, crawled.sourceUrl()))
                .thenReturn(Optional.of(old));
        when(indexWriter.write(any(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
            DocumentRecord candidate = invocation.getArgument(0);
            return new DocumentWriteResult(DocumentWriteStatus.UPDATED, storedDocument(55L, candidate));
        });

        IngestRunDto result = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(storage.deletedKeys).containsExactly("documents/old.html");
    }

    @Test
    void forceOmitsValidatorsButStillDeduplicatesAnUnchangedDocument() {
        CrawledDocument crawled = crawled("forced.html", "same");
        crawler.documents = List.of(crawled);
        when(documentRepository.findBySourceIdAndSourceUrl(10L, crawled.sourceUrl()))
                .thenReturn(Optional.of(existing(
                        crawled.sourceUrl(), sha256(crawled.rawBytes()), "documents/old.html")));

        service.run(ADMIN, 10L, new RunIngestRequest(true, false, null));

        assertThat(parser.calls).isZero();
        assertThat(embeddingGateway.calls).isZero();
        assertThat(storage.savedKeys).isEmpty();
        verify(indexWriter, never()).write(any(), anyBoolean(), any(), any());
    }

    @Test
    void rebuildVectorsReembedsUnchangedContentWhileReusingTheOldObject() {
        CrawledDocument crawled = crawled("rebuild.html", "same");
        crawler.documents = List.of(crawled);
        DocumentRecord old = existing(
                crawled.sourceUrl(), sha256(crawled.rawBytes()), "documents/reusable.html");
        when(documentRepository.findBySourceIdAndSourceUrl(10L, crawled.sourceUrl()))
                .thenReturn(Optional.of(old));

        service.run(ADMIN, 10L, new RunIngestRequest(false, true, null));

        assertThat(parser.calls).isEqualTo(1);
        assertThat(embeddingGateway.calls).isEqualTo(1);
        assertThat(storage.savedKeys).isEmpty();
        ArgumentCaptor<DocumentRecord> candidate = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(indexWriter).write(
                candidate.capture(), org.mockito.ArgumentMatchers.eq(true), any(), any());
        assertThat(candidate.getValue().fileObjectKey()).isEqualTo("documents/reusable.html");
    }

    @Test
    void documentFailuresAreCountedAndProcessingContinues() {
        crawler.documents = List.of(
                crawled("parse-fail.html", "one"),
                crawled("embed-fail.html", "two"),
                crawled("persist-fail.html", "three"),
                crawled("success.html", "four"));
        parser.failures.add("parse-fail.html");
        embeddingGateway.failureMarker = "embed-fail.html";
        when(indexWriter.write(any(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
            DocumentRecord candidate = invocation.getArgument(0);
            if (candidate.sourceUrl().contains("persist-fail")) {
                throw new IllegalStateException("db secret should not leak");
            }
            return new DocumentWriteResult(DocumentWriteStatus.ADDED, storedDocument(200L, candidate));
        });

        IngestRunDto result = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.PARTIAL_SUCCESS);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(3);
        assertThat(parser.calls).isEqualTo(4);
        assertThat(result.errorDetail()).doesNotContain("secret", "one", "two", "three", "four");
        assertThat(result.errorDetail()).contains("[parse=1, embedding=1, index=1]");
    }

    @Test
    void allDocumentFailuresProduceFailedStatus() {
        crawler.documents = List.of(crawled("parse-fail.html", "secret body"));
        parser.failures.add("parse-fail.html");

        IngestRunDto result = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.FAILED);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errorDetail()).doesNotContain("secret body");
    }

    @Test
    void crawlerFailureOrEmptyResultFinishesTheStartedRunAsFailed() {
        crawler.failure = new IllegalStateException("secret api key and body");
        IngestRunDto failed = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));
        assertThat(failed.status()).isEqualTo(IngestRunStatus.FAILED);
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.errorDetail()).doesNotContain("secret", "body", "key");

        crawler.failure = null;
        crawler.documents = List.of();
        IngestRunDto empty = service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));
        assertThat(empty.status()).isEqualTo(IngestRunStatus.FAILED);
        assertThat(empty.failed()).isEqualTo(1);
    }

    @Test
    void rateLimitedCrawlDefersTheRunWithoutFinishingIt() {
        OffsetDateTime retryAt = NOW.plusMinutes(5);
        crawler.failure = new CrawlDeferredException(retryAt);
        IngestRunDto deferred = new IngestRunDto(
                900L, 10L, IngestRunStatus.DEFERRED, 0, 0, 0, 0,
                "Remote source requested a deferred retry", NOW, null);
        when(runRepository.defer(900L, retryAt)).thenReturn(deferred);

        IngestRunDto result = service.run(
                ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result).isSameAs(deferred);
        verify(runRepository).defer(900L, retryAt);
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void newObjectIsDeletedWhenTransactionalIndexingFails() {
        crawler.documents = List.of(crawled("db-fail.html", "content"));
        when(indexWriter.write(any(), anyBoolean(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(storage.savedKeys).hasSize(1);
        assertThat(storage.deletedKeys).containsExactlyElementsOf(storage.savedKeys);
    }

    @Test
    void duplicateRunningFailureFromRepositoryIsPropagated() {
        BusinessException duplicate = new BusinessException(
                ErrorCode.INGEST_ALREADY_RUNNING, "Ingestion already running");
        when(runRepository.start(10L, null)).thenThrow(duplicate);

        assertThatThrownBy(() -> service.run(
                ADMIN, 10L, new RunIngestRequest(false, false, null)))
                .isSameAs(duplicate);
        assertThat(crawler.calls).isZero();
        verify(runRepository, never()).finish(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void asyncStartReturnsRunningBeforeQueuedJobExecutes() {
        ControllableExecutor executor = new ControllableExecutor();
        IngestionService asyncService = serviceWithExecutor(executor);
        crawler.documents = List.of(crawled("async.html", "content"));

        IngestRunDto running = asyncService.start(
                ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(running.status()).isEqualTo(IngestRunStatus.RUNNING);
        assertThat(crawler.calls).isZero();
        assertThat(executor.queued()).isEqualTo(1);

        executor.runNext();

        assertThat(crawler.calls).isEqualTo(1);
        verify(runRepository).finish(
                org.mockito.ArgumentMatchers.eq(900L),
                org.mockito.ArgumentMatchers.eq(IngestRunStatus.SUCCESS),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(0L),
                isNull());
    }

    @Test
    void manualRunClaimUsesAnAtomicAuditBoundary() {
        AdminAuditService auditService = mock(AdminAuditService.class);
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        IngestionService auditedService = new IngestionService(
                sourceRepository,
                documentRepository,
                runRepository,
                crawler,
                parser,
                new TextChunker(),
                embeddingGateway,
                storage,
                indexWriter,
                command -> { },
                auditService);

        auditedService.start(ADMIN, 10L, new RunIngestRequest(true, false, null));

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).audited(event.capture(), any());
        assertThat(event.getValue().action()).isEqualTo("INGESTION_REQUESTED");
        assertThat(event.getValue().targetType()).isEqualTo("SOURCE");
        assertThat(event.getValue().targetId()).isEqualTo(10L);
        assertThat(event.getValue().summary()).containsEntry("force", true);
    }

    @Test
    void manualRunTerminalStateUsesTheSameAtomicAuditBoundary() {
        AdminAuditService auditService = mock(AdminAuditService.class);
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(auditService.auditedOutcome(any(), any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        IngestionService auditedService = new IngestionService(
                sourceRepository,
                documentRepository,
                runRepository,
                crawler,
                parser,
                new TextChunker(),
                embeddingGateway,
                storage,
                indexWriter,
                Runnable::run,
                auditService);

        IngestRunDto result = auditedService.run(
                ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.FAILED);
        ArgumentCaptor<AdminAuditEvent> terminal = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).auditedOutcome(terminal.capture(), any(), any());
        assertThat(terminal.getValue().action()).isEqualTo("INGESTION_FINISHED");
        assertThat(terminal.getValue().targetType()).isEqualTo("INGEST_RUN");
        assertThat(terminal.getValue().targetId()).isEqualTo(900L);
        assertThat(terminal.getValue().actorId()).isEqualTo(ADMIN.userId());
    }

    @Test
    void rejectedAsyncSubmissionFinishesRunAndReturnsGenericIngestFailure() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("SECRET queue detail");
        };
        IngestionService asyncService = serviceWithExecutor(rejecting);

        assertThatThrownBy(() -> asyncService.start(
                        ADMIN, 10L, new RunIngestRequest(false, false, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("SECRET");
                    assertThat(exception.getCause()).isInstanceOf(RejectedExecutionException.class);
                });
        verify(runRepository).finish(
                900L, IngestRunStatus.FAILED, 0, 0, 0, 1, "Background submission failed");
    }

    @Test
    void rejectedScheduledClaimUsesItsLeaseToFinishAndUnregister() {
        IngestRunItemRepository itemRepository = mock(IngestRunItemRepository.class);
        IngestRunLeaseGuard leaseGuard = mock(IngestRunLeaseGuard.class);
        IngestRunLeaseHeartbeatScheduler heartbeatScheduler =
                mock(IngestRunLeaseHeartbeatScheduler.class);
        Executor rejecting = command -> {
            throw new RejectedExecutionException("SECRET queue detail");
        };
        IngestionService durableService = new IngestionService(
                sourceRepository,
                documentRepository,
                runRepository,
                crawler,
                parser,
                new TextChunker(),
                embeddingGateway,
                storage,
                indexWriter,
                itemRepository,
                leaseGuard,
                heartbeatScheduler,
                rejecting);
        IngestRunLease lease = new IngestRunLease(
                900L, 0, "scheduled-worker", java.util.UUID.randomUUID());
        ScheduledIngestClaim claim = new ScheduledIngestClaim(
                source(true), runningRun(), lease, List.of());
        IngestRunDto failed = new IngestRunDto(
                900L, 10L, IngestRunStatus.FAILED, 0, 0, 0, 1,
                "Background submission failed", NOW, NOW.plusMinutes(1));
        when(runRepository.finish(
                lease,
                IngestRunStatus.FAILED,
                0,
                0,
                0,
                1,
                "SUBMISSION_REJECTED",
                "Background submission failed")).thenReturn(true);
        when(runRepository.findById(900L)).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> durableService.submitClaimed(claim))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("SECRET");
                });

        verify(heartbeatScheduler).register(lease);
        verify(runRepository).finish(
                lease,
                IngestRunStatus.FAILED,
                0,
                0,
                0,
                1,
                "SUBMISSION_REJECTED",
                "Background submission failed");
        verify(heartbeatScheduler).unregister(lease);
    }

    @Test
    void cleanupRetriesThreeTimesWithoutOverridingPrimaryResult() {
        CrawledDocument crawled = crawled("cleanup.html", "new content");
        crawler.documents = List.of(crawled);
        when(documentRepository.findBySourceIdAndSourceUrl(10L, crawled.sourceUrl()))
                .thenReturn(Optional.of(existing(
                        crawled.sourceUrl(), "old-hash", "documents/old.html")));
        when(indexWriter.write(any(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
            DocumentRecord candidate = invocation.getArgument(0);
            return new DocumentWriteResult(DocumentWriteStatus.UPDATED, storedDocument(55L, candidate));
        });
        storage.deleteFailuresRemaining.put("documents/old.html", 3);

        IngestRunDto result = service.run(
                ADMIN, 10L, new RunIngestRequest(false, false, null));

        assertThat(result.status()).isEqualTo(IngestRunStatus.SUCCESS);
        assertThat(storage.deleteAttempts.get("documents/old.html")).isEqualTo(3);
    }

    @Test
    void storageFilenameIsTruncatedByUtf8BytesWithoutSplittingCodePoints() {
        String longChineseFilename = "课".repeat(200) + ".html";
        crawler.documents = List.of(crawled(longChineseFilename, "content"));

        service.run(ADMIN, 10L, new RunIngestRequest(false, false, null));

        String storedFilename = storage.savedFilenames.getFirst();
        assertThat(storedFilename.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(180);
        assertThat(storedFilename).doesNotContain("\uFFFD");
    }

    private IngestionService serviceWithExecutor(Executor executor) {
        return new IngestionService(
                sourceRepository,
                documentRepository,
                runRepository,
                crawler,
                parser,
                new TextChunker(),
                embeddingGateway,
                storage,
                indexWriter,
                executor);
    }

    private void assertAdminFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ADMIN_REQUIRED));
    }

    private void assertSourceFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND));
    }

    private SourceRecord source(boolean enabled) {
        return new SourceRecord(10L, "Registrar", "https://example.edu/source", "OFFICIAL",
                enabled, NOW, NOW);
    }

    private IngestRunDto runningRun() {
        return new IngestRunDto(
                900L, 10L, IngestRunStatus.RUNNING, 0, 0, 0, 0, null, NOW, null);
    }

    private CrawledDocument crawled(String filename, String content) {
        return new CrawledDocument(
                "https://example.edu/" + filename,
                filename,
                "text/html",
                NOW.minusDays(1),
                content.getBytes(StandardCharsets.UTF_8));
    }

    private DocumentRecord existing(String url, String hash, String objectKey) {
        return new DocumentRecord(
                44L, 10L, "Old title", url, "Registrar", NOW.minusDays(2),
                hash, objectKey, NOW.minusDays(1));
    }

    private DocumentRecord storedDocument(Long id, DocumentRecord candidate) {
        return new DocumentRecord(
                id,
                candidate.sourceId(),
                candidate.title(),
                candidate.sourceUrl(),
                candidate.sourceOrg(),
                candidate.publishedAt(),
                candidate.contentHash(),
                candidate.fileObjectKey(),
                NOW);
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FakeCrawler implements SourceCrawler {

        private List<CrawledDocument> documents = List.of();
        private RuntimeException failure;
        private int calls;

        @Override
        public List<CrawledDocument> crawl(SourceRecord source, boolean force) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return documents;
        }
    }

    private static final class FakeParser implements DocumentParser {

        private final Set<String> failures = new HashSet<>();
        private int calls;

        @Override
        public ParsedDocument parse(InputStream input, String resourceName) {
            calls++;
            if (failures.contains(resourceName)) {
                throw new BusinessException(ErrorCode.INGEST_FAILED, "Parser failed");
            }
            return new ParsedDocument(
                    "Parsed " + resourceName,
                    "Parsed body for " + resourceName,
                    "text/html",
                    Map.of("author", "Registrar"));
        }
    }

    private static final class FakeEmbeddingGateway implements EmbeddingGateway {

        private int calls;
        private String failureMarker;

        @Override
        public List<Double> embed(String text) {
            return embedAll(List.of(text)).getFirst();
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            calls++;
            if (failureMarker != null && texts.stream().anyMatch(text -> text.contains(failureMarker))) {
                throw new BusinessException(ErrorCode.EMBEDDING_FAILED, "Embedding failed");
            }
            return texts.stream().map(ignored -> vector()).toList();
        }

        @Override
        public int dimension() {
            return EmbeddingDimensions.DIMENSIONS;
        }

        private List<Double> vector() {
            List<Double> values = new ArrayList<>(
                    java.util.Collections.nCopies(EmbeddingDimensions.DIMENSIONS, 0.0));
            values.set(0, 1.0);
            return List.copyOf(values);
        }
    }

    private static final class FakeStorage implements ObjectStorage {

        private final List<String> savedKeys = new ArrayList<>();
        private final List<String> savedFilenames = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, Integer> deleteAttempts = new HashMap<>();
        private final Map<String, Integer> deleteFailuresRemaining = new HashMap<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public StoredObject save(String namespace, String filename, InputStream input) {
            try {
                byte[] bytes = input.readAllBytes();
                String key = namespace + "/new-" + (savedKeys.size() + 1) + "-" + filename;
                savedKeys.add(key);
                savedFilenames.add(filename);
                objects.put(key, bytes);
                return new StoredObject(key, filename, bytes.length);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public InputStream open(String objectKey) {
            return new ByteArrayInputStream(objects.get(objectKey));
        }

        @Override
        public void delete(String objectKey) {
            deleteAttempts.merge(objectKey, 1, Integer::sum);
            int remaining = deleteFailuresRemaining.getOrDefault(objectKey, 0);
            if (remaining > 0) {
                deleteFailuresRemaining.put(objectKey, remaining - 1);
                throw new BusinessException(ErrorCode.STORAGE_FAILED, "SECRET cleanup failure");
            }
            deletedKeys.add(objectKey);
            objects.remove(objectKey);
        }

        @Override
        public List<StoredObjectMetadata> list(String namespace) {
            return objects.entrySet().stream()
                    .filter(entry -> {
                        int separator = entry.getKey().indexOf('/');
                        String storedNamespace = entry.getKey().substring(0, separator);
                        return storedNamespace.equals(namespace)
                                || storedNamespace.startsWith(namespace + "-");
                    })
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new StoredObjectMetadata(
                            entry.getKey(), entry.getValue().length, Instant.EPOCH))
                    .toList();
        }
    }

    private static final class ControllableExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int queued() {
            return tasks.size();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }
}

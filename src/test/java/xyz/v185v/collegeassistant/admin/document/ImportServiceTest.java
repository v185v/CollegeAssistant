package xyz.v185v.collegeassistant.admin.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.ingestion.application.DocumentIngestionPipeline;
import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemStatus;
import xyz.v185v.collegeassistant.ingestion.IngestRunLease;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseGuard;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseHeartbeatScheduler;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.application.IngestionRunCoordinator;
import xyz.v185v.collegeassistant.ingestion.LeaseGuardedIngestOperations;

class ImportServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());

    private IngestionRunCoordinator coordinator;
    private IngestRunRepository runs;
    private IngestRunLeaseGuard guard;
    private IngestRunLeaseHeartbeatScheduler heartbeats;
    private ImportStagingStore staging;
    private DocumentIngestionPipeline pipeline;
    private DocumentRepository documents;
    private LeaseGuardedIngestOperations operations;
    private ImportService service;
    private IngestRunLease lease;
    private IngestRunItemDto item;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        coordinator = mock(IngestionRunCoordinator.class);
        runs = mock(IngestRunRepository.class);
        guard = mock(IngestRunLeaseGuard.class);
        heartbeats = mock(IngestRunLeaseHeartbeatScheduler.class);
        staging = mock(ImportStagingStore.class);
        pipeline = mock(DocumentIngestionPipeline.class);
        documents = mock(DocumentRepository.class);
        operations = mock(LeaseGuardedIngestOperations.class);
        Executor queued = command -> { };
        service = new ImportService(
                coordinator, runs, guard, heartbeats, staging, pipeline, documents, queued);

        lease = new IngestRunLease(
                80L, 0, "worker", UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        item = new IngestRunItemDto(
                90L, 80L, "client-a", "FILE", IngestRunItemStatus.QUEUED,
                null, 0, null, null, null, Map.of(), 0, NOW, NOW);
        SourceRecord source = localSource();
        IngestRunDto running = new IngestRunDto(
                80L, 10L, IngestRunStatus.RUNNING, 0, 0, 0, 0, null, NOW, null);
        when(coordinator.claimImport(eq(10L), anyString(), any(), anyString()))
                .thenReturn(new IngestionRunCoordinator.ClaimedImportRun(
                        source, running, lease, List.of(item)));
        when(guard.inTransaction(eq(lease), any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedIngestOperations, Object> work = invocation.getArgument(1);
            return work.apply(operations);
        });
        when(staging.save(eq(80L), eq(90L), any(ByteArrayInputStream.class)))
                .thenReturn(new StagedImportObject(
                        80L, 90L, 4L, Instant.parse("2026-07-25T00:00:00Z"),
                        "import-staging/80/90"));
        when(documents.findActiveInternalBySourceIdAndContentHash(eq(10L), anyString()))
                .thenReturn(Optional.empty());
        when(documents.findActiveInternalBySourceIdAndNormalizedFilename(10L, "rules.txt"))
                .thenReturn(Optional.empty());
        when(runs.findDetailById(80L)).thenReturn(Optional.of(detail()));
    }

    @Test
    void preservesFileOrderAndEchoesClientIdsThroughDurableItems() {
        ImportMetadata metadata = metadata("client-a");

        IngestRunDetailDto result = service.start(
                ADMIN, metadata, List.of(file("rules.txt", "text")));

        assertThat(result.items()).extracting(IngestRunItemDto::itemKey)
                .containsExactly("client-a");
        verify(coordinator).claimImport(eq(10L), eq("local upload"), eq(List.of("client-a")),
                org.mockito.ArgumentMatchers.startsWith("import-"));
        ArgumentCaptor<Map<String, Object>> checkpoint = ArgumentCaptor.forClass(Map.class);
        verify(operations).checkpoint(
                eq(90L), eq(IngestRunItemStatus.QUEUED), eq(null), checkpoint.capture());
        assertThat(checkpoint.getValue()).containsEntry("clientId", "client-a")
                .containsEntry("originalFilename", "rules.txt")
                .containsEntry("stagingRunId", 80L)
                .doesNotContainValue("client-a/rules.txt");
    }

    @Test
    void importClaimAndRequestAuditShareOneBoundary() {
        AdminAuditService auditService = mock(AdminAuditService.class);
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        service = new ImportService(
                coordinator, runs, guard, heartbeats, staging, pipeline, documents,
                command -> { }, auditService);

        service.start(ADMIN, metadata("client-a"), List.of(file("rules.txt", "text")));

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).audited(event.capture(), any());
        assertThat(event.getValue().action()).isEqualTo("IMPORT_REQUESTED");
        assertThat(event.getValue().targetType()).isEqualTo("SOURCE");
        assertThat(event.getValue().targetId()).isEqualTo(10L);
        assertThat(event.getValue().summary()).containsEntry("fileCount", 1);
    }

    @Test
    void rejectsCountAndDeclaredSizeBeforeClaimingTheSource() {
        assertValidation(() -> service.start(
                ADMIN, new ImportMetadata(10L, List.of()), List.of(file("rules.txt", "text"))));
        MultipartFile tooLarge = mock(MultipartFile.class);
        when(tooLarge.getOriginalFilename()).thenReturn("rules.txt");
        when(tooLarge.getSize()).thenReturn((long) ImportService.MAX_FILE_BYTES + 1);
        assertValidation(() -> service.start(
                ADMIN, metadata("client-a"), List.of(tooLarge)));

        verify(coordinator, never()).claimImport(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void enforcesTheBatchLimitAgainstBytesActuallyRead() {
        assertValidation(() -> ImportService.accumulateBatchBytes(
                ImportService.MAX_BATCH_BYTES, 1));
    }

    @Test
    void rejectsExecutableBytesDisguisedWithAnAllowedExtension() {
        MockMultipartFile executable = new MockMultipartFile(
                "files", "rules.pdf", "application/pdf", new byte[] {'M', 'Z', 0, 0});

        assertValidation(() -> service.start(
                ADMIN, metadata("client-a"), List.of(executable)));

        verify(staging, never()).save(anyLong(), anyLong(), any());
        verify(runs).finish(
                eq(lease), eq(IngestRunStatus.FAILED), eq(0L), eq(0L), eq(0L), eq(1L),
                eq("IMPORT_REJECTED"), anyString());
    }

    @Test
    void sameFilenameWithDifferentContentRequiresExplicitReplacement() {
        when(documents.findActiveInternalBySourceIdAndNormalizedFilename(10L, "rules.txt"))
                .thenReturn(Optional.of(mock(DocumentRecord.class)));

        assertThatThrownBy(() -> service.start(
                ADMIN, metadata("client-a"), List.of(file("rules.txt", "new text"))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.DOCUMENT_REPLACEMENT_REQUIRED));
    }

    @Test
    void sameFilenameWithDifferentContentInOneBatchRequiresReplacement() {
        Map<String, String> filenameHashes = new LinkedHashMap<>();
        ImportService.preflightBatchIdentity(filenameHashes, "rules.txt", "hash-a", null);

        assertThatThrownBy(() -> ImportService.preflightBatchIdentity(
                filenameHashes, "rules.txt", "hash-b", null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.DOCUMENT_REPLACEMENT_REQUIRED));
    }

    @Test
    void newlyStagedCheckpointIsVisibleToTheSubmittedWorker() throws Exception {
        service = synchronousService();
        when(staging.open(80L, 90L)).thenReturn(new ByteArrayInputStream(
                "text".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(pipeline.ingest(any(), any(), eq(90L))).thenReturn(
                new DocumentIngestionPipeline.PipelineResult(
                        DocumentWriteStatus.ADDED, mock(DocumentRecord.class)));

        service.start(ADMIN, metadata("client-a"), List.of(file("rules.txt", "text")));

        ArgumentCaptor<DocumentIngestionPipeline.PipelineRequest> request =
                ArgumentCaptor.forClass(DocumentIngestionPipeline.PipelineRequest.class);
        verify(pipeline).ingest(request.capture(), any(), eq(90L));
        assertThat(request.getValue().filename()).isEqualTo("rules.txt");
        verify(staging).delete(80L, 90L);
    }

    @Test
    void importTerminalStateUsesAnAtomicLeaseFencedAuditBoundary() throws Exception {
        AdminAuditService auditService = mock(AdminAuditService.class);
        when(auditService.audited(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(auditService.auditedOutcome(any(), any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        service = new ImportService(
                coordinator, runs, guard, heartbeats, staging, pipeline, documents,
                Runnable::run, auditService);
        when(staging.open(80L, 90L)).thenReturn(new ByteArrayInputStream(
                "text".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(pipeline.ingest(any(), any(), eq(90L))).thenReturn(
                new DocumentIngestionPipeline.PipelineResult(
                        DocumentWriteStatus.ADDED, mock(DocumentRecord.class)));

        service.start(ADMIN, metadata("client-a"), List.of(file("rules.txt", "text")));

        ArgumentCaptor<AdminAuditEvent> terminal = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).auditedOutcome(terminal.capture(), any(), any());
        assertThat(terminal.getValue().action()).isEqualTo("IMPORT_FINISHED");
        assertThat(terminal.getValue().targetType()).isEqualTo("INGEST_RUN");
        assertThat(terminal.getValue().targetId()).isEqualTo(80L);
        assertThat(terminal.getValue().actorId()).isEqualTo(ADMIN.userId());
    }

    @Test
    void failedProcessingRetainsStagingCoordinatesForRetry() throws Exception {
        service = synchronousService();
        when(staging.open(80L, 90L)).thenReturn(new ByteArrayInputStream(
                "text".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(pipeline.ingest(any(), any(), eq(90L)))
                .thenThrow(new IllegalStateException("embedding unavailable"));

        service.start(ADMIN, metadata("client-a"), List.of(file("rules.txt", "text")));

        ArgumentCaptor<Map<String, Object>> checkpoint = ArgumentCaptor.forClass(Map.class);
        verify(operations).checkpoint(
                eq(90L), eq(IngestRunItemStatus.FAILED), eq(null), checkpoint.capture());
        assertThat(checkpoint.getValue())
                .containsEntry("stagingRunId", 80L)
                .containsEntry("stagingItemId", 90L)
                .containsEntry("originalFilename", "rules.txt")
                .containsEntry("stage", "unknown");
    }

    private void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private ImportService synchronousService() {
        return new ImportService(
                coordinator, runs, guard, heartbeats, staging, pipeline, documents, Runnable::run);
    }

    private ImportMetadata metadata(String clientId) {
        return new ImportMetadata(10L, List.of(new ImportFileMetadata(
                clientId, "Rules", null, null, null, null, null, null)));
    }

    private MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile(
                "files", filename, "text/plain",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private SourceRecord localSource() {
        return new SourceRecord(
                10L, "Uploads", null, "OFFICIAL", true,
                SourceConnectorType.LOCAL_UPLOAD, "NONE", SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 3, 500, 4, List.of(), List.of(),
                null, null, 1L, NOW, NOW);
    }

    private IngestRunDetailDto detail() {
        return new IngestRunDetailDto(
                80L, 10L, IngestRunStatus.RUNNING, "IMPORT", null, 1,
                0, 0, 0, 0, 0, "worker", NOW, NOW,
                null, null, NOW, null, List.of(item));
    }
}

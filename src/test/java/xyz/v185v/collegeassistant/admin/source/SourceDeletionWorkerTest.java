package xyz.v185v.collegeassistant.admin.source;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.job.LeaseGuardedJobOperations;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

class SourceDeletionWorkerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void deletesVectorsObjectsDocumentsAndSourceBehindTheJobLease() {
        SourceRepository sourceRepository = mock(SourceRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        EmbeddingChunkRepository embeddingRepository = mock(EmbeddingChunkRepository.class);
        IngestRunRepository runRepository = mock(IngestRunRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        AdminJobItemDto item = new AdminJobItemDto(
                90L, 40L, "document:20", "DOCUMENT", "DOCUMENT", 20L,
                AdminJobStatus.QUEUED, 0, null, null, Map.of(), 0, NOW, NOW);
        AdminJobDto job = new AdminJobDto(
                40L, AdminJobType.SOURCE_DELETE, AdminJobStatus.RUNNING, "SOURCE", 10L, 1L,
                0, "worker", null, NOW, NOW, NOW, null, null, null, Map.of(), NOW, NOW, List.of());
        when(context.job()).thenReturn(job);
        when(documentRepository.findDeletionCandidates(10L)).thenReturn(
                List.of(new SourceDeletionCandidate(20L, "documents/object-20")));
        when(context.upsertItem(eq("document:20"), eq("DOCUMENT"), eq("DOCUMENT"), eq(20L)))
                .thenReturn(item);
        when(context.upsertItem(eq("source:10"), eq("SOURCE"), eq("SOURCE"), eq(10L)))
                .thenReturn(new AdminJobItemDto(
                        91L, 40L, "source:10", "SOURCE", "SOURCE", 10L,
                        AdminJobStatus.QUEUED, 0, null, null, Map.of(), 0, NOW, NOW));
        when(sourceRepository.resumeDeleting(10L)).thenReturn(true);
        when(sourceRepository.deleteDeleting(10L)).thenReturn(true);
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });

        new SourceDeletionWorker(
                sourceRepository, documentRepository, embeddingRepository, runRepository, storage)
                .execute(context);

        verify(embeddingRepository).deleteAllForReference("DOCUMENT", 20L);
        verify(storage).delete("documents/object-20");
        verify(documentRepository).deleteById(20L);
        verify(runRepository).deleteForSource(10L);
        verify(sourceRepository).resumeDeleting(10L);
        verify(sourceRepository).deleteDeleting(10L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storageFailureMarksTheSourceFailedWithoutDeletingTheDocumentRow() {
        SourceRepository sourceRepository = mock(SourceRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        EmbeddingChunkRepository embeddingRepository = mock(EmbeddingChunkRepository.class);
        IngestRunRepository runRepository = mock(IngestRunRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        AdminJobItemDto item = new AdminJobItemDto(
                90L, 40L, "document:20", "DOCUMENT", "DOCUMENT", 20L,
                AdminJobStatus.QUEUED, 0, null, null, Map.of(), 0, NOW, NOW);
        AdminJobDto job = new AdminJobDto(
                40L, AdminJobType.SOURCE_DELETE, AdminJobStatus.RUNNING, "SOURCE", 10L, 1L,
                0, "worker", null, NOW, NOW, NOW, null, null, null, Map.of(), NOW, NOW, List.of());
        when(context.job()).thenReturn(job);
        when(documentRepository.findDeletionCandidates(10L)).thenReturn(
                List.of(new SourceDeletionCandidate(20L, "documents/object-20")));
        when(context.upsertItem(eq("document:20"), eq("DOCUMENT"), eq("DOCUMENT"), eq(20L)))
                .thenReturn(item);
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });
        doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).delete("documents/object-20");

        assertThatThrownBy(() -> new SourceDeletionWorker(
                sourceRepository, documentRepository, embeddingRepository, runRepository, storage)
                .execute(context))
                .isInstanceOf(IllegalStateException.class);

        verify(sourceRepository).markDeleteFailed(10L);
        verify(documentRepository, never()).deleteById(20L);
        verify(sourceRepository, never()).deleteDeleting(10L);
    }
}

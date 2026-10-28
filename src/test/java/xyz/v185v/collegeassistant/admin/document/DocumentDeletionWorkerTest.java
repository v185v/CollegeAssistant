package xyz.v185v.collegeassistant.admin.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.job.LeaseGuardedJobOperations;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

class DocumentDeletionWorkerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void removesAllVectorVersionsBeforeTheObjectAndDeletingRow() {
        DocumentRepository documents = mock(DocumentRepository.class);
        EmbeddingChunkRepository embeddings = mock(EmbeddingChunkRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AdminJobExecutionContext context = context(Map.of());
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        when(documents.findById(20L)).thenReturn(java.util.Optional.of(document()));
        when(documents.resumeDeleting(20L)).thenReturn(true);
        when(documents.deleteDeleting(20L)).thenReturn(true);
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });

        new DocumentDeletionWorker(documents, embeddings, storage).execute(context);

        InOrder order = inOrder(embeddings, storage, documents);
        order.verify(embeddings).deleteAllForReference("DOCUMENT", 20L);
        order.verify(storage).delete("documents/object-20");
        order.verify(documents).deleteDeleting(20L);
        verify(operations).checkpoint(eq(90L), eq(AdminJobStatus.RUNNING),
                eq(Map.of("stage", "VECTORS_DELETED")));
        verify(operations).checkpoint(eq(90L), eq(AdminJobStatus.RUNNING),
                eq(Map.of("stage", "OBJECT_DELETED")));
        verify(operations).checkpoint(eq(90L), eq(AdminJobStatus.SUCCEEDED),
                eq(Map.of("stage", "DOCUMENT_DELETED")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryResumesAfterTheLastDurableCheckpoint() {
        DocumentRepository documents = mock(DocumentRepository.class);
        EmbeddingChunkRepository embeddings = mock(EmbeddingChunkRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AdminJobExecutionContext context = context(Map.of("stage", "VECTORS_DELETED"));
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        when(documents.findById(20L)).thenReturn(java.util.Optional.of(document()));
        when(documents.resumeDeleting(20L)).thenReturn(true);
        when(documents.deleteDeleting(20L)).thenReturn(true);
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });

        new DocumentDeletionWorker(documents, embeddings, storage).execute(context);

        verify(embeddings, never()).deleteAllForReference(any(), any());
        verify(storage).delete("documents/object-20");
        verify(documents).deleteDeleting(20L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void objectFailureMarksTheDocumentDeleteFailedAndKeepsItsRow() {
        DocumentRepository documents = mock(DocumentRepository.class);
        EmbeddingChunkRepository embeddings = mock(EmbeddingChunkRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AdminJobExecutionContext context = context(Map.of());
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        when(documents.findById(20L)).thenReturn(java.util.Optional.of(document()));
        when(documents.resumeDeleting(20L)).thenReturn(true);
        when(context.inLeaseTransaction(any(Function.class))).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });
        doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).delete("documents/object-20");

        assertThatThrownBy(() -> new DocumentDeletionWorker(documents, embeddings, storage)
                .execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage unavailable");

        verify(documents).markDeleteFailed(20L);
        verify(documents, never()).deleteDeleting(20L);
    }

    private AdminJobExecutionContext context(Map<String, Object> checkpoint) {
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        when(context.job()).thenReturn(new AdminJobDto(
                40L, AdminJobType.DOCUMENT_DELETE, AdminJobStatus.RUNNING,
                "DOCUMENT", 20L, 1L, 1, "worker", null, NOW, NOW, NOW,
                null, null, null, Map.of(), NOW, NOW, List.of()));
        when(context.upsertItem("document:20", "DOCUMENT", "DOCUMENT", 20L))
                .thenReturn(new AdminJobItemDto(
                        90L, 40L, "document:20", "DOCUMENT", "DOCUMENT", 20L,
                        AdminJobStatus.RUNNING, 0, null, null, checkpoint, 1, NOW, NOW));
        return context;
    }

    private DocumentRecord document() {
        return new DocumentRecord(
                20L, 10L, "Guide", "https://example.edu/guide", "Registrar",
                NOW, "hash", "documents/object-20", NOW);
    }
}

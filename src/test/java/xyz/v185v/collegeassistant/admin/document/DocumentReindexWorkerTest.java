package xyz.v185v.collegeassistant.admin.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.application.DocumentIngestionPipeline;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

class DocumentReindexWorkerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");

    @Test
    void reopensTheOwnedObjectRebuildsVectorsAndKeepsInternalIdentity() {
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceRepository sources = mock(SourceRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        AdminJobExecutionContext context = context();
        DocumentRecord document = document(DocumentStatus.ACTIVE);
        SourceRecord source = source("NONE");
        TrackingInputStream input = new TrackingInputStream("fresh content".getBytes());
        when(documents.findById(20L)).thenReturn(java.util.Optional.of(document));
        when(sources.findById(10L)).thenReturn(java.util.Optional.of(source));
        when(storage.open("documents/object-20")).thenReturn(input);
        when(pipeline.ingestForAdminJob(any(), eq(context), eq(90L)))
                .thenReturn(new DocumentIngestionPipeline.PipelineResult(
                        DocumentWriteStatus.UPDATED, document));

        new DocumentReindexWorker(documents, sources, storage, pipeline).execute(context);

        ArgumentCaptor<DocumentIngestionPipeline.PipelineRequest> request =
                ArgumentCaptor.forClass(DocumentIngestionPipeline.PipelineRequest.class);
        verify(pipeline).ingestForAdminJob(request.capture(), eq(context), eq(90L));
        assertThat(request.getValue().rawBytes()).isEqualTo("fresh content".getBytes());
        assertThat(request.getValue().replaceDocumentId()).isEqualTo(20L);
        assertThat(request.getValue().referenceType()).isEqualTo(DocumentReferenceType.INTERNAL);
        assertThat(request.getValue().rebuildVectors()).isTrue();
        assertThat(document.internalLocator())
                .isEqualTo(UUID.fromString("9f735650-c80a-4ce9-a788-03ebfed144ac"));
        assertThat(input.closed).isTrue();
        verify(context).checkpoint(90L, AdminJobStatus.SUCCEEDED,
                Map.of("stage", "REINDEXED", "documentId", 20L));
    }

    @Test
    void deletingDocumentIsRejectedBeforeOpeningStorage() {
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceRepository sources = mock(SourceRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        AdminJobExecutionContext context = context();
        when(documents.findById(20L)).thenReturn(
                java.util.Optional.of(document(DocumentStatus.DELETING)));

        assertThatThrownBy(() -> new DocumentReindexWorker(
                documents, sources, storage, pipeline).execute(context))
                .isInstanceOf(BusinessException.class);

        verify(storage, never()).open(any());
        verify(pipeline, never()).ingestForAdminJob(any(), any(), any());
    }

    @Test
    void deletingSourceIsRejectedBeforeOpeningStorage() {
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceRepository sources = mock(SourceRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        AdminJobExecutionContext context = context();
        when(documents.findById(20L)).thenReturn(
                java.util.Optional.of(document(DocumentStatus.ACTIVE)));
        when(sources.findById(10L)).thenReturn(java.util.Optional.of(source("DELETING")));

        assertThatThrownBy(() -> new DocumentReindexWorker(
                documents, sources, storage, pipeline).execute(context))
                .isInstanceOf(BusinessException.class);

        verify(storage, never()).open(any());
        verify(pipeline, never()).ingestForAdminJob(any(), any(), any());
    }

    private AdminJobExecutionContext context() {
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        when(context.job()).thenReturn(new AdminJobDto(
                40L, AdminJobType.DOCUMENT_REINDEX, AdminJobStatus.RUNNING,
                "DOCUMENT", 20L, 1L, 1, "worker", null, NOW, NOW, NOW,
                null, null, null, Map.of(), NOW, NOW, List.of()));
        when(context.upsertItem("document:20", "DOCUMENT", "DOCUMENT", 20L))
                .thenReturn(new AdminJobItemDto(
                        90L, 40L, "document:20", "DOCUMENT", "DOCUMENT", 20L,
                        AdminJobStatus.RUNNING, 0, null, null, Map.of(), 1, NOW, NOW));
        return context;
    }

    private DocumentRecord document(DocumentStatus status) {
        UUID locator = UUID.fromString("9f735650-c80a-4ce9-a788-03ebfed144ac");
        return new DocumentRecord(
                20L, 10L, "Guide", "internal://document/" + locator, "Registrar",
                NOW, "hash", "documents/object-20", NOW, DocumentReferenceType.INTERNAL,
                "https://example.edu/guide", locator, "guide.pdf", "guide.pdf", status,
                null, null, NOW, NOW, "GENERAL", "GENERAL", "GENERAL", "GENERAL");
    }

    private SourceRecord source(String deletionStatus) {
        return new SourceRecord(
                10L, "Registrar", null, "OFFICIAL", true,
                SourceConnectorType.LOCAL_UPLOAD, deletionStatus, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 0, 100, 1, List.of(), List.of(),
                null, null, 1L, NOW, NOW);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}

package xyz.v185v.collegeassistant.admin.document;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobHandler;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.crawl.CrawledDocument;
import xyz.v185v.collegeassistant.ingestion.application.DocumentIngestionPipeline;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

/** 从不可变对象重建所有向量版本，同时保留文档身份。 */
@Component
public class DocumentReindexWorker implements AdminJobHandler {

    private final DocumentRepository documentRepository;
    private final SourceRepository sourceRepository;
    private final ObjectStorage objectStorage;
    private final DocumentIngestionPipeline pipeline;
    private EmbeddingMaintenanceGuard maintenanceGuard;

    public DocumentReindexWorker(
            DocumentRepository documentRepository,
            SourceRepository sourceRepository,
            ObjectStorage objectStorage,
            DocumentIngestionPipeline pipeline
    ) {
        this.documentRepository = documentRepository;
        this.sourceRepository = sourceRepository;
        this.objectStorage = objectStorage;
        this.pipeline = pipeline;
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

    @Override
    public AdminJobType type() {
        return AdminJobType.DOCUMENT_REINDEX;
    }

    @Override
    public void execute(AdminJobExecutionContext context) {
        requireWritesAllowed();
        Long documentId = requireTarget(context);
        AdminJobItemDto item = context.upsertItem(
                "document:" + documentId, "DOCUMENT", "DOCUMENT", documentId);
        if (item.status() == AdminJobStatus.SUCCEEDED) {
            return;
        }
        DocumentRecord document = documentRepository.findById(documentId)
                .orElseThrow(() -> notFound("Document not found"));
        requireActive(document);
        SourceRecord source = sourceRepository.findById(document.sourceId())
                .orElseThrow(() -> notFound("Document source not found"));
        if (!"NONE".equals(source.deletionStatus())) {
            throw conflict("Document source is being deleted");
        }
        if (document.fileObjectKey() == null || document.fileObjectKey().isBlank()) {
            throw conflict("Document has no stored object to reindex");
        }

        context.assertActive();
        byte[] rawBytes = readOwnedObject(document.fileObjectKey());
        context.assertActive();
        DocumentIngestionPipeline.PipelineRequest request = new DocumentIngestionPipeline.PipelineRequest(
                source, rawBytes, filename(document), document.title(), document.externalSourceUrl(),
                document.publishedAt(), document.referenceType(), document.id(), document.degree(),
                document.college(), document.campus(), document.gradeRange(), true, true);
        DocumentIngestionPipeline.PipelineResult result =
                pipeline.ingestForAdminJob(request, context, item.id());
        context.checkpoint(item.id(), AdminJobStatus.SUCCEEDED,
                Map.of("stage", "REINDEXED", "documentId", result.document().id()));
    }

    private byte[] readOwnedObject(String objectKey) {
        try (InputStream input = objectStorage.open(objectKey)) {
            byte[] bytes = input.readNBytes(CrawledDocument.MAX_RAW_BYTES + 1);
            if (bytes.length > CrawledDocument.MAX_RAW_BYTES) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_FAILED, "Stored document exceeds the reindex size limit");
            }
            return bytes;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.STORAGE_FAILED, "Stored document cannot be read", exception);
        }
    }

    private String filename(DocumentRecord document) {
        if (document.originalFilename() != null && !document.originalFilename().isBlank()) {
            return document.originalFilename();
        }
        try {
            String path = URI.create(document.sourceUrl()).getPath();
            int separator = path == null ? -1 : path.lastIndexOf('/');
            String leaf = path == null ? null : path.substring(separator + 1);
            if (leaf != null && !leaf.isBlank()) {
                return leaf;
            }
        } catch (IllegalArgumentException ignored) {
// 回退到稳定标识符。
        }
        return "document-" + document.id();
    }

    private void requireActive(DocumentRecord document) {
        if (document.documentStatus() != DocumentStatus.ACTIVE) {
            throw conflict("Document is not active");
        }
    }

    private Long requireTarget(AdminJobExecutionContext context) {
        Long target = context.job().targetId();
        if (target == null || target <= 0) {
            throw new IllegalArgumentException("Document-reindex jobs require a positive target");
        }
        return target;
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}

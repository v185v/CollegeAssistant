package xyz.v185v.collegeassistant.admin.document;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobHandler;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobLeaseLostException;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

/** 可安全重启的文档删除流程：先删向量，再删不可变对象，最后删关系数据。 */
@Component
public class DocumentDeletionWorker implements AdminJobHandler {

    private static final List<String> STAGES = List.of(
            "QUEUED", "VECTORS_DELETED", "OBJECT_DELETED", "DOCUMENT_DELETED");

    private final DocumentRepository documentRepository;
    private final EmbeddingChunkRepository embeddingRepository;
    private final ObjectStorage objectStorage;
    private EmbeddingMaintenanceGuard maintenanceGuard;

    public DocumentDeletionWorker(
            DocumentRepository documentRepository,
            EmbeddingChunkRepository embeddingRepository,
            ObjectStorage objectStorage
    ) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.objectStorage = objectStorage;
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

    @Override
    public AdminJobType type() {
        return AdminJobType.DOCUMENT_DELETE;
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
        DocumentRecord document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            context.checkpoint(item.id(), AdminJobStatus.SUCCEEDED,
                    Map.of("stage", "DOCUMENT_DELETED"));
            return;
        }
        String stage = String.valueOf(item.checkpoint().getOrDefault("stage", "QUEUED"));
        try {
            claim(context, documentId);
            if (!reached(stage, "VECTORS_DELETED")) {
                context.inLeaseTransaction(operations -> {
                    embeddingRepository.deleteAllForReference("DOCUMENT", documentId);
                    operations.checkpoint(item.id(), AdminJobStatus.RUNNING,
                            Map.of("stage", "VECTORS_DELETED"));
                    return null;
                });
                stage = "VECTORS_DELETED";
            }
            if (!reached(stage, "OBJECT_DELETED")) {
                context.assertActive();
                if (document.fileObjectKey() != null && !document.fileObjectKey().isBlank()) {
                    objectStorage.delete(document.fileObjectKey());
                }
                context.inLeaseTransaction(operations -> {
                    operations.checkpoint(item.id(), AdminJobStatus.RUNNING,
                            Map.of("stage", "OBJECT_DELETED"));
                    return null;
                });
            }
            context.inLeaseTransaction(operations -> {
                if (!documentRepository.deleteDeleting(documentId)
                        && documentRepository.findById(documentId).isPresent()) {
                    throw new IllegalStateException("Document deletion claim is no longer active");
                }
                operations.checkpoint(item.id(), AdminJobStatus.SUCCEEDED,
                        Map.of("stage", "DOCUMENT_DELETED"));
                return null;
            });
        } catch (AdminJobLeaseLostException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            markDeleteFailed(context, documentId);
            throw exception;
        }
    }

    private void claim(AdminJobExecutionContext context, Long documentId) {
        context.inLeaseTransaction(operations -> {
            if (!documentRepository.resumeDeleting(documentId)) {
                throw new IllegalStateException("Document deletion claim is no longer recoverable");
            }
            return null;
        });
    }

    private void markDeleteFailed(AdminJobExecutionContext context, Long documentId) {
        try {
            context.inLeaseTransaction(operations -> {
                documentRepository.markDeleteFailed(documentId);
                return null;
            });
        } catch (AdminJobLeaseLostException ignored) {
// 重试任务现在拥有该生命周期状态。
        }
    }

    private boolean reached(String current, String expected) {
        int currentIndex = STAGES.indexOf(current);
        int expectedIndex = STAGES.indexOf(expected);
        return currentIndex >= expectedIndex && expectedIndex >= 0;
    }

    private Long requireTarget(AdminJobExecutionContext context) {
        Long target = context.job().targetId();
        if (target == null || target <= 0) {
            throw new IllegalArgumentException("Document-delete jobs require a positive target");
        }
        return target;
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }
}

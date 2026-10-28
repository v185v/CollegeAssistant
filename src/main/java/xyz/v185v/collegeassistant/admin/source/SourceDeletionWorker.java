package xyz.v185v.collegeassistant.admin.source;

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
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

/** 可恢复的数据源删除处理器；数据库变更始终共享活动任务的租约锁。 */
@Component
public class SourceDeletionWorker implements AdminJobHandler {

    private final SourceRepository sourceRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingChunkRepository embeddingRepository;
    private final IngestRunRepository runRepository;
    private final ObjectStorage objectStorage;
    private EmbeddingMaintenanceGuard maintenanceGuard;

    public SourceDeletionWorker(
            SourceRepository sourceRepository,
            DocumentRepository documentRepository,
            EmbeddingChunkRepository embeddingRepository,
            IngestRunRepository runRepository,
            ObjectStorage objectStorage
    ) {
        this.sourceRepository = sourceRepository;
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
        this.runRepository = runRepository;
        this.objectStorage = objectStorage;
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

    @Override
    public AdminJobType type() {
        return AdminJobType.SOURCE_DELETE;
    }

    @Override
    public void execute(AdminJobExecutionContext context) {
        requireWritesAllowed();
        Long sourceId = context.job().targetId();
        if (sourceId == null || sourceId <= 0) {
            throw new IllegalArgumentException("Source-delete jobs require a positive source target");
        }
        try {
            List<SourceDeletionCandidate> candidates = documentRepository.findDeletionCandidates(sourceId);
            for (SourceDeletionCandidate candidate : candidates) {
                deleteCandidate(context, candidate);
            }
            deleteSource(context, sourceId);
        } catch (AdminJobLeaseLostException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            markDeleteFailed(context, sourceId);
            throw exception;
        }
    }

    private void deleteCandidate(
            AdminJobExecutionContext context,
            SourceDeletionCandidate candidate
    ) {
        AdminJobItemDto item = context.upsertItem(
                "document:" + candidate.documentId(),
                "DOCUMENT",
                "DOCUMENT",
                candidate.documentId());
        if (item.status() == AdminJobStatus.SUCCEEDED) {
            return;
        }
        context.inLeaseTransaction(operations -> {
            embeddingRepository.deleteAllForReference("DOCUMENT", candidate.documentId());
            operations.checkpoint(item.id(), AdminJobStatus.RUNNING, Map.of("stage", "VECTORS_DELETED"));
            return null;
        });
        context.assertActive();
        if (candidate.objectKey() != null && !candidate.objectKey().isBlank()) {
            objectStorage.delete(candidate.objectKey());
        }
        context.inLeaseTransaction(operations -> {
            documentRepository.deleteById(candidate.documentId());
            operations.checkpoint(item.id(), AdminJobStatus.SUCCEEDED, Map.of("stage", "DOCUMENT_DELETED"));
            return null;
        });
    }

    private void deleteSource(AdminJobExecutionContext context, Long sourceId) {
        AdminJobItemDto item = context.upsertItem(
                "source:" + sourceId,
                "SOURCE",
                "SOURCE",
                sourceId);
        if (item.status() == AdminJobStatus.SUCCEEDED) {
            return;
        }
        context.inLeaseTransaction(operations -> {
            if (!sourceRepository.resumeDeleting(sourceId)) {
                throw new IllegalStateException("Source deletion claim is no longer recoverable");
            }
            runRepository.deleteForSource(sourceId);
            if (!sourceRepository.deleteDeleting(sourceId)) {
                throw new IllegalStateException("Source deletion claim is no longer active");
            }
            operations.checkpoint(item.id(), AdminJobStatus.SUCCEEDED, Map.of("stage", "SOURCE_DELETED"));
            return null;
        });
    }

    private void markDeleteFailed(AdminJobExecutionContext context, Long sourceId) {
        try {
            context.inLeaseTransaction(operations -> {
                sourceRepository.markDeleteFailed(sourceId);
                return null;
            });
        } catch (AdminJobLeaseLostException ignored) {
// 重试任务现在拥有数据源生命周期状态。
        }
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }
}

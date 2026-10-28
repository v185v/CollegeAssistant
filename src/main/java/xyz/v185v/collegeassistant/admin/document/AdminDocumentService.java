package xyz.v185v.collegeassistant.admin.document;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

/** 索引文档管理的授权与生命周期边界。 */
@Service
public class AdminDocumentService {

    private static final String SOURCE_ACTIVE = "NONE";

    private final DocumentRepository documentRepository;
    private final SourceRepository sourceRepository;
    private final AdminJobService jobService;
    private final ObjectStorage objectStorage;
    private EmbeddingMaintenanceGuard maintenanceGuard;

    public AdminDocumentService(
            DocumentRepository documentRepository,
            SourceRepository sourceRepository,
            AdminJobService jobService,
            ObjectStorage objectStorage
    ) {
        this.documentRepository = documentRepository;
        this.sourceRepository = sourceRepository;
        this.jobService = jobService;
        this.objectStorage = objectStorage;
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

    public PageResult<DocumentDto> list(
            AuthPrincipal actor,
            int page,
            int size,
            Long sourceId,
            DocumentStatus status,
            String title,
            DocumentReferenceType referenceType,
            OffsetDateTime ingestedFrom,
            OffsetDateTime ingestedTo
    ) {
        requireAdmin(actor);
        if (title != null && title.strip().length() > 512) {
            throw validation("Document title filter is too long");
        }
        if (ingestedFrom != null && ingestedTo != null && ingestedFrom.isAfter(ingestedTo)) {
            throw validation("Document ingestion date range is invalid");
        }
        PageResult<DocumentRecord> records = documentRepository.find(
                page, size, sourceId, status, normalize(title), referenceType,
                ingestedFrom, ingestedTo);
        return new PageResult<>(
                records.items().stream().map(this::toDto).toList(),
                records.total(), records.page(), records.size(), records.pages());
    }

    public DocumentDto get(AuthPrincipal actor, Long documentId) {
        requireAdmin(actor);
        return toDto(requireDocument(documentId));
    }

/** 仅打开文档和来源都未处于删除生命周期中的内容。 */
    public DownloadableDocument openDownload(AuthPrincipal actor, Long documentId) {
        requireAuthenticated(actor);
        DocumentRecord document = documentRepository.findAvailableForContent(documentId)
                .orElseThrow(() -> conflict("Document is unavailable for download"));
        if (!hasObject(document)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Document content is unavailable");
        }
        String filename = displayFilename(document);
        InputStream input = objectStorage.open(document.fileObjectKey());
        try {
            String contentType = MediaTypeFactory.getMediaType(filename)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM)
                    .toString();
            return new DownloadableDocument(input, contentType, filename);
        } catch (RuntimeException exception) {
            closeQuietly(input);
            throw exception;
        }
    }

    @Transactional
    public AdminJobDto reindex(AuthPrincipal actor, Long documentId) {
        requireAdmin(actor);
        requireWritesAllowed();
        DocumentRecord locked = lockAvailable(documentId, true);
        return jobService.create(
                actor, AdminJobType.DOCUMENT_REINDEX, "DOCUMENT", locked.id(),
                Map.of("documentId", locked.id()));
    }

/** 在同一数据库事务中标记记录并创建持久化任务。 */
    @Transactional
    public AdminJobDto requestDeletion(AuthPrincipal actor, Long documentId) {
        requireAdmin(actor);
        requireWritesAllowed();
        DocumentRecord locked = lockAvailable(documentId, false);
        if (!documentRepository.markDeleting(locked.id())) {
            throw conflict("Document deletion is already in progress");
        }
        return jobService.create(
                actor, AdminJobType.DOCUMENT_DELETE, "DOCUMENT", locked.id(),
                Map.of("documentId", locked.id()));
    }

    private DocumentRecord lockAvailable(Long documentId, boolean requireObject) {
        DocumentRecord snapshot = requireDocument(documentId);
        SourceRecord source = sourceRepository.lockForMutation(snapshot.sourceId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SOURCE_NOT_FOUND, "Document source not found"));
        if (!SOURCE_ACTIVE.equals(source.deletionStatus())) {
            throw conflict("Document source is being deleted");
        }
        DocumentRecord locked = documentRepository.lockForMutation(documentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Document not found"));
        if (!snapshot.sourceId().equals(locked.sourceId())) {
            throw conflict("Document source changed during the operation");
        }
        if (requireObject && locked.documentStatus() != DocumentStatus.ACTIVE) {
            throw conflict("Document is not active");
        }
        if (!requireObject
                && locked.documentStatus() != DocumentStatus.ACTIVE
                && locked.documentStatus() != DocumentStatus.FAILED) {
            throw conflict("Document cannot be deleted in its current state");
        }
        if (requireObject && !hasObject(locked)) {
            throw conflict("Document has no stored object to reindex");
        }
        return locked;
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }

    private DocumentRecord requireDocument(Long documentId) {
        return documentRepository.findById(documentId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Document not found"));
    }

    private DocumentDto toDto(DocumentRecord document) {
        return new DocumentDto(
                document.id(), document.sourceId(), document.title(), document.sourceUrl(),
                document.sourceOrg(), document.publishedAt(), document.contentHash(),
                document.referenceType(), document.externalSourceUrl(), document.originalFilename(),
                document.documentStatus(), hasObject(document)
                        && document.documentStatus() == DocumentStatus.ACTIVE,
                document.ingestedAt(),
                document.lastSeenAt(), document.lastCheckedAt(), document.degree(),
                document.college(), document.campus(), document.gradeRange());
    }

    private boolean hasObject(DocumentRecord document) {
        return document.fileObjectKey() != null && !document.fileObjectKey().isBlank();
    }

    private String displayFilename(DocumentRecord document) {
        if (document.originalFilename() != null && !document.originalFilename().isBlank()) {
            return document.originalFilename();
        }
        String external = document.externalSourceUrl();
        if (external != null && !external.isBlank()) {
            try {
                String path = URI.create(external).getRawPath();
                int separator = path == null ? -1 : path.lastIndexOf('/');
                String leaf = path == null ? null : path.substring(separator + 1);
                if (leaf != null && !leaf.isBlank()) {
                    return UriUtils.decode(leaf, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException ignored) {
// 已存储的旧 URL 仍可使用下面的确定性回退逻辑。
            }
        }
        return "document-" + document.id();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private void requireAuthenticated(AuthPrincipal actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
// 最初的构造失败仍是最终依据。
        }
    }
}

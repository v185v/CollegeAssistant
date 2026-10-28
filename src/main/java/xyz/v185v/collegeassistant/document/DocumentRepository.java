package xyz.v185v.collegeassistant.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.source.SourceDeletionCandidate;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.persistence.DocumentDO;
import xyz.v185v.collegeassistant.document.persistence.DocumentMapper;

/** 文档持久化与生命周期决策的领域适配器。 */
@Repository
public class DocumentRepository {

    private final DocumentMapper mapper;

    public DocumentRepository(DocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public DocumentWriteResult write(DocumentRecord candidate, boolean force) {
        Objects.requireNonNull(candidate, "candidate");
        mapper.lockIdentity(
                candidate.sourceId() + ":" + candidate.sourceUrl().length() + ":" + candidate.sourceUrl());
        Optional<DocumentRecord> existing = findBySourceIdAndSourceUrl(candidate.sourceId(), candidate.sourceUrl());
        if (existing.isEmpty()) {
            return new DocumentWriteResult(
                    DocumentWriteStatus.ADDED,
                    mapDocument(mapper.insertDocument(toDataObject(candidate))));
        }
        DocumentRecord stored = existing.orElseThrow();
        if (!force && Objects.equals(stored.contentHash(), candidate.contentHash())) {
            return new DocumentWriteResult(DocumentWriteStatus.UNCHANGED, stored);
        }
        return new DocumentWriteResult(DocumentWriteStatus.UPDATED,
                mapDocument(mapper.updateDocument(stored.id(), toDataObject(candidate))));
    }

    public Optional<DocumentRecord> findById(Long id) {
        requirePositive(id, "Document id");
        return Optional.ofNullable(mapper.selectById(id)).map(this::mapDocument);
    }

    public Optional<DocumentRecord> findBySourceIdAndSourceUrl(Long sourceId, String sourceUrl) {
        requirePositive(sourceId, "Document sourceId");
        requireText(sourceUrl, "Document sourceUrl");
        return Optional.ofNullable(mapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                        .eq(DocumentDO::getSourceId, sourceId)
                        .eq(DocumentDO::getSourceUrl, sourceUrl)))
                .map(this::mapDocument);
    }

    public PageResult<DocumentRecord> find(int page, int size, Long sourceId, DocumentStatus status,
            String title, DocumentReferenceType referenceType, OffsetDateTime ingestedFrom, OffsetDateTime ingestedTo) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        if (sourceId != null) {
            requirePositive(sourceId, "Document sourceId");
        }
        String statusValue = status == null ? null : status.name();
        String referenceTypeValue = referenceType == null ? null : referenceType.name();
        long total = mapper.countFiltered(sourceId, statusValue, title, referenceTypeValue, ingestedFrom, ingestedTo);
        List<DocumentRecord> items = mapper.findFiltered(sourceId, statusValue, title, referenceTypeValue,
                        ingestedFrom, ingestedTo, size, (long) (page - 1) * size).stream()
                .map(this::mapDocument).toList();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(List.copyOf(items), total, page, size, pages);
    }

    public Optional<DocumentRecord> lockForMutation(Long id) {
        requirePositive(id, "Document id");
        return Optional.ofNullable(mapper.lockForMutation(id)).map(this::mapDocument);
    }

    public Optional<DocumentRecord> findAvailableForContent(Long id) {
        requirePositive(id, "Document id");
        return Optional.ofNullable(mapper.findAvailableForContent(id)).map(this::mapDocument);
    }

    public Optional<DocumentRecord> lockActiveForReindex(Long id) {
        requirePositive(id, "Document id");
        return Optional.ofNullable(mapper.lockActiveForReindex(id)).map(this::mapDocument);
    }

    public List<DocumentEmbeddingRebuildCandidate> findActiveForEmbeddingRebuild() {
        return mapper.findActiveForEmbeddingRebuild().stream()
                .map(row -> new DocumentEmbeddingRebuildCandidate(row.getId(), row.getContentHash()))
                .toList();
    }

    public Optional<DocumentRecord> lockActiveForEmbeddingRebuild(Long id) {
        requirePositive(id, "Document id");
        return Optional.ofNullable(mapper.lockActiveForEmbeddingRebuild(id)).map(this::mapDocument);
    }

/** 原子认领活动文档以执行持久化删除。 */
    public boolean markDeleting(Long id) {
        requirePositive(id, "Document id");
        return mapper.update(null, Wrappers.<DocumentDO>lambdaUpdate()
                .set(DocumentDO::getDocumentStatus, "DELETING")
                .eq(DocumentDO::getId, id)
                .in(DocumentDO::getDocumentStatus, "ACTIVE", "FAILED")) == 1;
    }

    public boolean markDeleteFailed(Long id) {
        requirePositive(id, "Document id");
        return mapper.update(null, Wrappers.<DocumentDO>lambdaUpdate()
                .set(DocumentDO::getDocumentStatus, "DELETE_FAILED")
                .eq(DocumentDO::getId, id)
                .eq(DocumentDO::getDocumentStatus, "DELETING")) == 1;
    }

    public boolean resumeDeleting(Long id) {
        requirePositive(id, "Document id");
        return mapper.update(null, Wrappers.<DocumentDO>lambdaUpdate()
                .set(DocumentDO::getDocumentStatus, "DELETING")
                .eq(DocumentDO::getId, id)
                .in(DocumentDO::getDocumentStatus, "DELETING", "DELETE_FAILED")) == 1;
    }

    public boolean deleteDeleting(Long id) {
        requirePositive(id, "Document id");
        return mapper.delete(Wrappers.<DocumentDO>lambdaQuery()
                .eq(DocumentDO::getId, id)
                .eq(DocumentDO::getDocumentStatus, "DELETING")) == 1;
    }

/** 查找具有相同不可变内容摘要的活动本地上传。 */
    public Optional<DocumentRecord> findActiveInternalBySourceIdAndContentHash(
            Long sourceId,
            String contentHash
    ) {
        requirePositive(sourceId, "Document sourceId");
        requireText(contentHash, "Document contentHash");
        return Optional.ofNullable(mapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                        .eq(DocumentDO::getSourceId, sourceId)
                        .eq(DocumentDO::getContentHash, contentHash)
                        .eq(DocumentDO::getReferenceType, "INTERNAL")
                        .notIn(DocumentDO::getDocumentStatus, "DELETING", "DELETE_FAILED")))
                .map(this::mapDocument);
    }

/** 查找任意生命周期状态的本地上传身份，防止摄取绕过删除流程。 */
    public Optional<DocumentRecord> findInternalBySourceIdAndContentHash(
            Long sourceId,
            String contentHash
    ) {
        requirePositive(sourceId, "Document sourceId");
        requireText(contentHash, "Document contentHash");
        return mapper.selectPage(new Page<DocumentDO>(1, 1, false),
                        Wrappers.<DocumentDO>lambdaQuery()
                                .eq(DocumentDO::getSourceId, sourceId)
                                .eq(DocumentDO::getContentHash, contentHash)
                                .eq(DocumentDO::getReferenceType, "INTERNAL")
                                .orderByDesc(DocumentDO::getId))
                .getRecords().stream().findFirst().map(this::mapDocument);
    }

/** 按规范化的用户可见文件名查找活动本地上传。 */
    public Optional<DocumentRecord> findActiveInternalBySourceIdAndNormalizedFilename(
            Long sourceId,
            String normalizedFilename
    ) {
        requirePositive(sourceId, "Document sourceId");
        requireText(normalizedFilename, "Document normalizedFilename");
        return Optional.ofNullable(mapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                        .eq(DocumentDO::getSourceId, sourceId)
                        .eq(DocumentDO::getNormalizedFilename, normalizedFilename)
                        .eq(DocumentDO::getReferenceType, "INTERNAL")
                        .notIn(DocumentDO::getDocumentStatus, "DELETING", "DELETE_FAILED")))
                .map(this::mapDocument);
    }

/** 即使身份已进入删除状态，也查找规范化本地文件名。 */
    public Optional<DocumentRecord> findInternalBySourceIdAndNormalizedFilename(
            Long sourceId,
            String normalizedFilename
    ) {
        requirePositive(sourceId, "Document sourceId");
        requireText(normalizedFilename, "Document normalizedFilename");
        return mapper.selectPage(new Page<DocumentDO>(1, 1, false),
                        Wrappers.<DocumentDO>lambdaQuery()
                                .eq(DocumentDO::getSourceId, sourceId)
                                .eq(DocumentDO::getNormalizedFilename, normalizedFilename)
                                .eq(DocumentDO::getReferenceType, "INTERNAL")
                                .orderByDesc(DocumentDO::getId))
                .getRecords().stream().findFirst().map(this::mapDocument);
    }

    public Optional<DocumentRecord> findByInternalLocator(UUID locator) {
        if (locator == null) {
            throw new IllegalArgumentException("Document internal locator must not be null");
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                .eq(DocumentDO::getInternalLocator, locator))).map(this::mapDocument);
    }

/** 列出数据源删除任务所需的稳定标识符和不可变存储键。 */
    public List<SourceDeletionCandidate> findDeletionCandidates(Long sourceId) {
        requirePositive(sourceId, "Document sourceId");
        return mapper.selectList(Wrappers.<DocumentDO>lambdaQuery()
                        .select(DocumentDO::getId, DocumentDO::getFileObjectKey)
                        .eq(DocumentDO::getSourceId, sourceId)
                        .orderByAsc(DocumentDO::getId)).stream()
                .map(row -> new SourceDeletionCandidate(row.getId(), row.getFileObjectKey()))
                .toList();
    }

/** 在处理其向量和不可变对象后幂等删除一个文档。 */
    public boolean deleteById(Long documentId) {
        requirePositive(documentId, "Document id");
        return mapper.deleteById(documentId) <= 1;
    }

    private DocumentDO toDataObject(DocumentRecord candidate) {
        DocumentDO row = new DocumentDO();
        row.setId(candidate.id());
        row.setSourceId(candidate.sourceId());
        row.setTitle(candidate.title());
        row.setSourceUrl(candidate.sourceUrl());
        row.setSourceOrg(candidate.sourceOrg());
        row.setPublishedAt(candidate.publishedAt());
        row.setContentHash(candidate.contentHash());
        row.setFileObjectKey(candidate.fileObjectKey());
        row.setIngestedAt(candidate.ingestedAt());
        row.setReferenceType(candidate.referenceType().name());
        row.setExternalSourceUrl(candidate.externalSourceUrl());
        row.setInternalLocator(candidate.internalLocator());
        row.setOriginalFilename(candidate.originalFilename());
        row.setNormalizedFilename(candidate.normalizedFilename());
        row.setDocumentStatus(candidate.documentStatus().name());
        row.setEtag(candidate.etag());
        row.setHttpLastModified(candidate.httpLastModified());
        row.setLastSeenAt(candidate.lastSeenAt());
        row.setLastCheckedAt(candidate.lastCheckedAt());
        row.setDegree(candidate.degree());
        row.setCollege(candidate.college());
        row.setCampus(candidate.campus());
        row.setGradeRange(candidate.gradeRange());
        return row;
    }

    private DocumentRecord mapDocument(DocumentDO row) {
        return new DocumentRecord(
                row.getId(),
                row.getSourceId(),
                row.getTitle(),
                row.getSourceUrl(),
                row.getSourceOrg(),
                row.getPublishedAt(),
                row.getContentHash(),
                row.getFileObjectKey(),
                row.getIngestedAt(),
                DocumentReferenceType.valueOf(row.getReferenceType()),
                row.getExternalSourceUrl(),
                row.getInternalLocator(),
                row.getOriginalFilename(),
                row.getNormalizedFilename(),
                DocumentStatus.valueOf(row.getDocumentStatus()),
                row.getEtag(),
                row.getHttpLastModified(),
                row.getLastSeenAt(),
                row.getLastCheckedAt(),
                row.getDegree(),
                row.getCollege(),
                row.getCampus(),
                row.getGradeRange());
    }

    private void requirePositive(Long id, String field) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

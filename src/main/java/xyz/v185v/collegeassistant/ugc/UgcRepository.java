package xyz.v185v.collegeassistant.ugc;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.ugc.persistence.UgcMapper;
import xyz.v185v.collegeassistant.ugc.persistence.UgcPostDO;
import xyz.v185v.collegeassistant.ugc.persistence.UgcPostRow;
import xyz.v185v.collegeassistant.ugc.persistence.UgcReportDO;

/** 保留审核和触发器反馈语义的 UGC 持久化接口。 */
@Repository
public class UgcRepository {

    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {
    };

    private final UgcMapper mapper;
    private final ObjectMapper objectMapper;

    public UgcRepository(UgcMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

/** 创建待审核内容并返回作者投影。 */
    public UgcPostDto create(Long authorId, CreateUgcPostRequest request) {
        UgcPostDO post = new UgcPostDO();
        post.setTitle(request.title());
        post.setContent(request.content());
        post.setTagsJson(serializeTags(request.tags()));
        post.setRelatedCourse(request.relatedCourse());
        post.setRelatedLocation(request.relatedLocation());
        return mapPost(mapper.createReturningPost(authorId, post));
    }

/** 使用可为空的作者、状态和 JSONB 标签过滤条件分页查询帖子。 */
    public PageResult<UgcPostDto> findPage(
            Long authorId,
            UgcStatus status,
            String tag,
            int page,
            int size
    ) {
        String tagJson = tag == null || tag.isBlank() ? null : serializeTags(List.of(tag));
        String statusName = status == null ? null : status.name();
        long total = mapper.countPosts(authorId, statusName, tagJson);
        List<UgcPostDto> items = mapper.findPosts(
                        authorId, statusName, tagJson, size, calculateOffset(page, size))
                .stream()
                .map(this::mapPost)
                .toList();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(List.copyOf(items), total, page, size, pages);
    }

    static long calculateOffset(int page, int size) {
        return ((long) page - 1) * size;
    }

    public Optional<UgcPostDto> findById(Long id) {
        return Optional.ofNullable(mapper.findPostById(id)).map(this::mapPost);
    }

/** 将帖子设置为终态的已移除状态。 */
    public Optional<UgcPostDto> remove(Long id) {
        return Optional.ofNullable(mapper.removeReturningPost(id)).map(this::mapPost);
    }

/** 仅在帖子有资格接受公开反馈时查找它。 */
    public Optional<UgcPostDto> findApprovedById(Long id) {
        return Optional.ofNullable(mapper.findApprovedById(id)).map(this::mapPost);
    }

/** 创建反馈记录；数据库触发器维护聚合计数。 */
    public boolean ensureLike(Long postId, long userId) {
        return mapper.ensureLike(postId, userId) != null;
    }

/** 创建举报记录；数据库触发器维护聚合计数。 */
    public boolean ensureReport(Long postId, long reporterId, String reason) {
        return mapper.ensureReport(postId, reporterId, reason) != null;
    }

    public PageResult<UgcReportDto> findReports(Long postId, int page, int size) {
        long total = mapper.countReports(postId);
        List<UgcReportDto> items = mapper.findReports(postId, size, calculateOffset(page, size))
                .stream()
                .map(this::mapReport)
                .toList();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(List.copyOf(items), total, page, size, pages);
    }

    public Optional<UgcReportDto> findReport(Long postId, Long reportId) {
        return Optional.ofNullable(mapper.findReport(postId, reportId)).map(this::mapReport);
    }

/** 在举报仍处于打开状态时准确处理一次。 */
    public Optional<UgcReportDto> resolveReport(
            Long postId,
            Long reportId,
            Long reviewerId,
            UgcReportStatus status
    ) {
        return Optional.ofNullable(mapper.resolveReportReturning(
                postId, reportId, reviewerId, status.name())).map(this::mapReport);
    }

/** 锁定帖子记录并确认其仍处于已审核状态。 */
    public boolean lockIfApproved(Long id) {
        return UgcStatus.APPROVED.name().equals(mapper.lockIfApproved(id));
    }

/** 列出已审核 UGC 帖子并记录其确定性重建版本。 */
    public List<UgcEmbeddingRebuildCandidate> findApprovedForEmbeddingRebuild() {
        return mapper.findApprovedForEmbeddingRebuild().stream()
                .map(this::mapEmbeddingRebuildCandidate)
                .toList();
    }

/** 写入候选向量前锁定并重新加载已审核 UGC 帖子。 */
    public Optional<UgcEmbeddingRebuildCandidate> lockApprovedForEmbeddingRebuild(Long id) {
        requirePositive(id, "UGC post id");
        return Optional.ofNullable(mapper.lockApprovedForEmbeddingRebuild(id))
                .map(this::mapEmbeddingRebuildCandidate);
    }

/** 扫描当前没有 UGC 向量分块的已审核帖子。 */
    public List<Long> findApprovedWithoutChunksAfter(long afterId, int limit) {
        validateReconciliationRequest(afterId, limit);
        return List.copyOf(mapper.findApprovedWithoutChunksAfter(afterId, limit));
    }

    static void validateReconciliationRequest(long afterId, int limit) {
        if (afterId < 0) {
            throw new IllegalArgumentException("Reconciliation cursor must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Reconciliation limit must be positive");
        }
    }

/** 仅在帖子待审核期间写入终态审核决定。 */
    public Optional<UgcPostDto> review(
            Long id,
            Long reviewerId,
            ReviewDecision decision,
            String rejectReason
    ) {
        UgcStatus targetStatus = decision == ReviewDecision.APPROVE
                ? UgcStatus.APPROVED
                : UgcStatus.REJECTED;
        return Optional.ofNullable(mapper.reviewReturningPost(
                id, reviewerId, targetStatus.name(), rejectReason)).map(this::mapPost);
    }

    private UgcPostDto mapPost(UgcPostRow row) {
        return new UgcPostDto(
                row.id(), row.authorId(), row.authorName(), row.title(), row.content(),
                deserializeTags(row.tagsJson()), row.relatedCourse(), row.relatedLocation(),
                UgcStatus.valueOf(row.status()), row.likes(), row.reports(), row.rejectReason(),
                row.createdAt(), row.reviewedAt());
    }

    private UgcEmbeddingRebuildCandidate mapEmbeddingRebuildCandidate(UgcPostRow row) {
        UgcPostDto post = mapPost(row);
        return new UgcEmbeddingRebuildCandidate(
                post,
                UgcEmbeddingRebuildCandidate.contentVersion(
                        row.updatedAt(), post.title(), post.content(), row.tagsJson(),
                        post.relatedCourse(), post.relatedLocation()));
    }

    private UgcReportDto mapReport(UgcReportDO row) {
        return new UgcReportDto(
                row.getId(), row.getPostId(), row.getReporterId(), row.getReporterName(),
                row.getReason(), UgcReportStatus.valueOf(row.getStatus()), row.getCreatedAt());
    }

    private String serializeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize UGC tags", ex);
        }
    }

    List<String> deserializeTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(json, TAGS_TYPE);
            if (tags == null) {
                return List.of();
            }
            if (tags.contains(null)) {
                throw new IllegalStateException("UGC tags must not contain null elements");
            }
            return List.copyOf(tags);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize UGC tags", ex);
        }
    }

    private void requirePositive(Long id, String field) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}

package xyz.v185v.collegeassistant.rag;

import java.time.OffsetDateTime;
import java.util.Map;

import xyz.v185v.collegeassistant.chat.CitationReferenceType;

/**
 * 经过画像过滤和调权排序后的检索分块。
 *
 * @param chunkId    分块编号
 * @param chunkText  分块正文
 * @param sourceType 来源类型
 * @param score      最终相关性得分
 * @param sourceUrl  来源地址
 * @param title      来源标题
 * @param sourceOrg  来源机构
 * @param publishedAt 发布时间
 * @param meta       扩展元数据
 */
public record RetrievedChunk(
        Long chunkId,
        String chunkText,
        String sourceType,
        double score,
        String sourceUrl,
        String title,
        String sourceOrg,
        OffsetDateTime publishedAt,
        Map<String, Object> meta,
        CitationReferenceType referenceType,
        Long documentId,
        String externalSourceUrl
) {

/** 为不暴露稳定内部引用的调用方保留的兼容构造函数。 */
    public RetrievedChunk(
            Long chunkId,
            String chunkText,
            String sourceType,
            double score,
            String sourceUrl,
            String title,
            String sourceOrg,
            OffsetDateTime publishedAt,
            Map<String, Object> meta
    ) {
        this(
                chunkId,
                chunkText,
                sourceType,
                score,
                sourceUrl,
                title,
                sourceOrg,
                publishedAt,
                meta,
                defaultReferenceType(sourceType),
                null,
                null);
    }

    public RetrievedChunk {
        if (chunkId == null || chunkId <= 0) {
            throw new IllegalArgumentException("chunkId must be positive");
        }
        requireText(chunkText, "chunkText");
        requireText(sourceType, "sourceType");
        if (!"OFFICIAL".equals(sourceType) && !"UGC".equals(sourceType)) {
            throw new IllegalArgumentException("sourceType must be OFFICIAL or UGC");
        }
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be finite and between 0 and 1");
        }
        referenceType = referenceType == null
                ? defaultReferenceType(sourceType)
                : referenceType;
        if (!matchesSourceType(sourceType, referenceType)) {
            throw new IllegalArgumentException(
                    "referenceType must match the retrieved source type");
        }
        if (referenceType == CitationReferenceType.INTERNAL) {
            if (documentId == null || documentId <= 0) {
                throw new IllegalArgumentException(
                        "Internal retrieved chunks require a positive documentId");
            }
            if (sourceUrl != null) {
                throw new IllegalArgumentException(
                        "Internal retrieved chunks must not expose a source URL");
            }
        } else if (documentId != null || externalSourceUrl != null) {
            throw new IllegalArgumentException(
                    "Only internal retrieved chunks may contain document reference metadata");
        }
        meta = RetrievedMetadataSupport.immutableCopy(meta);
    }

    private static CitationReferenceType defaultReferenceType(String sourceType) {
        return "UGC".equals(sourceType)
                ? CitationReferenceType.UGC
                : CitationReferenceType.EXTERNAL;
    }

    private static boolean matchesSourceType(
            String sourceType,
            CitationReferenceType referenceType
    ) {
        return ("OFFICIAL".equals(sourceType)
                && (referenceType == CitationReferenceType.EXTERNAL
                || referenceType == CitationReferenceType.INTERNAL))
                || ("UGC".equals(sourceType) && referenceType == CitationReferenceType.UGC);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

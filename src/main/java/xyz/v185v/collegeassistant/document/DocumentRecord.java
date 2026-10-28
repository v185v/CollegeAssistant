package xyz.v185v.collegeassistant.document;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已摄取官方文档的持久化模型。
 *
 * @param id            文档编号
 * @param sourceId      所属数据源编号
 * @param title         文档标题
 * @param sourceUrl     原始来源地址
 * @param sourceOrg     来源机构
 * @param publishedAt   原文发布时间
 * @param contentHash   原始内容摘要，用于增量判断
 * @param fileObjectKey 原始文件对象键
 * @param ingestedAt    最近摄取时间
 */
public record DocumentRecord(
        Long id,
        Long sourceId,
        String title,
        String sourceUrl,
        String sourceOrg,
        OffsetDateTime publishedAt,
        String contentHash,
        String fileObjectKey,
        OffsetDateTime ingestedAt,
        DocumentReferenceType referenceType,
        String externalSourceUrl,
        UUID internalLocator,
        String originalFilename,
        String normalizedFilename,
        DocumentStatus documentStatus,
        String etag,
        OffsetDateTime httpLastModified,
        OffsetDateTime lastSeenAt,
        OffsetDateTime lastCheckedAt,
        String degree,
        String college,
        String campus,
        String gradeRange
) {

/** 为现有外部爬虫调用方和测试保留的兼容构造函数。 */
    public DocumentRecord(
            Long id,
            Long sourceId,
            String title,
            String sourceUrl,
            String sourceOrg,
            OffsetDateTime publishedAt,
            String contentHash,
            String fileObjectKey,
            OffsetDateTime ingestedAt
    ) {
        this(
                id, sourceId, title, sourceUrl, sourceOrg, publishedAt, contentHash,
                fileObjectKey, ingestedAt, DocumentReferenceType.EXTERNAL, sourceUrl,
                null, null, null, DocumentStatus.ACTIVE, null, null, ingestedAt,
                null, null, null, null, null);
    }

    public DocumentRecord {
        requirePositive(id, "Document id", true);
        requirePositive(sourceId, "Document sourceId", false);
        requireText(title, 512, "Document title");
        requireText(sourceUrl, null, "Document sourceUrl");
        requireText(contentHash, 128, "Document contentHash");
        requireLength(sourceOrg, 128, "Document sourceOrg");
        referenceType = referenceType == null ? DocumentReferenceType.EXTERNAL : referenceType;
        documentStatus = documentStatus == null ? DocumentStatus.ACTIVE : documentStatus;
        requireLength(externalSourceUrl, 4096, "Document externalSourceUrl");
        requireLength(originalFilename, 512, "Document originalFilename");
        requireLength(normalizedFilename, 512, "Document normalizedFilename");
        requireLength(etag, 512, "Document etag");
        requireLength(degree, 32, "Document degree");
        requireLength(college, 64, "Document college");
        requireLength(campus, 64, "Document campus");
        requireLength(gradeRange, 64, "Document gradeRange");
        if (referenceType == DocumentReferenceType.EXTERNAL) {
            if (internalLocator != null) {
                throw new IllegalArgumentException("External document must not have an internal locator");
            }
            externalSourceUrl = externalSourceUrl == null || externalSourceUrl.isBlank()
                    ? sourceUrl : externalSourceUrl;
        } else {
            if (internalLocator == null) {
                throw new IllegalArgumentException("Internal document requires an internal locator");
            }
            String expectedSourceUrl = "internal://document/" + internalLocator;
            if (!expectedSourceUrl.equals(sourceUrl)) {
                throw new IllegalArgumentException("Internal document sourceUrl must match its locator");
            }
            requireText(originalFilename, 512, "Internal document originalFilename");
            requireText(normalizedFilename, 512, "Internal document normalizedFilename");
        }
    }

    private static void requirePositive(Long value, String field, boolean nullable) {
        if (value == null) {
            if (!nullable) {
                throw new IllegalArgumentException(field + " must not be null");
            }
            return;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireText(String value, Integer maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (maxLength != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
    }

    private static void requireLength(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
    }
}

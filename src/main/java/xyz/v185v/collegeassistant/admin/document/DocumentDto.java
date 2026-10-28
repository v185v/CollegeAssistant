package xyz.v185v.collegeassistant.admin.document;

import java.time.OffsetDateTime;

import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentStatus;

/** 面向管理员的单个索引文档安全投影。 */
public record DocumentDto(
        Long id,
        Long sourceId,
        String title,
        String sourceUrl,
        String sourceOrg,
        OffsetDateTime publishedAt,
        String contentHash,
        DocumentReferenceType referenceType,
        String externalSourceUrl,
        String originalFilename,
        DocumentStatus documentStatus,
        boolean downloadable,
        OffsetDateTime ingestedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime lastCheckedAt,
        String degree,
        String college,
        String campus,
        String gradeRange
) {
}

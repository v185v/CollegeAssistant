package xyz.v185v.collegeassistant.ugc;

import java.time.OffsetDateTime;

/** 提供给管理员的持久化举报详情。 */
public record UgcReportDto(
        Long id,
        Long postId,
        Long reporterId,
        String reporterName,
        String reason,
        UgcReportStatus status,
        OffsetDateTime createdAt
) {
}

package xyz.v185v.collegeassistant.admin.audit;

import java.time.OffsetDateTime;
import java.util.Map;

/** 提供给管理员的安全脱敏审计记录。 */
public record AdminAuditLogDto(
        Long id,
        Long actorId,
        String action,
        String targetType,
        Long targetId,
        Map<String, Object> summary,
        String remoteAddress,
        AdminAuditResult result,
        Integer errorCode,
        OffsetDateTime createdAt
) {
}

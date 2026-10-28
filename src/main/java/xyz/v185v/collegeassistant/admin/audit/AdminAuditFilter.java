package xyz.v185v.collegeassistant.admin.audit;

import java.time.OffsetDateTime;

/** 管理员审计清单的可选固定结构过滤条件。 */
public record AdminAuditFilter(
        Long actorId,
        String action,
        String targetType,
        Long targetId,
        AdminAuditResult result,
        OffsetDateTime createdFrom,
        OffsetDateTime createdTo
) {
}

package xyz.v185v.collegeassistant.admin.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一次管理员变更的不可变、基于允许列表的描述。 */
public record AdminAuditEvent(
        Long actorId,
        String action,
        String targetType,
        Long targetId,
        Map<String, Object> summary,
        String remoteAddress,
        AdminAuditResult result,
        Integer errorCode
) {

    public AdminAuditEvent {
        if (actorId != null && actorId <= 0) {
            throw new IllegalArgumentException("actorId must be positive when present");
        }
        action = requiredText(action, 64, "action");
        targetType = requiredText(targetType, 32, "targetType");
        if (targetId != null && targetId <= 0) {
            throw new IllegalArgumentException("targetId must be positive when present");
        }
        summary = summary == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
        remoteAddress = remoteAddress == null || remoteAddress.isBlank()
                ? null
                : remoteAddress.strip();
        result = Objects.requireNonNull(result, "result");
    }

    public static AdminAuditEvent requested(
            Long actorId,
            String action,
            String targetType,
            Long targetId,
            Map<String, Object> summary
    ) {
        return requested(actorId, action, targetType, targetId, summary, null);
    }

    public static AdminAuditEvent requested(
            Long actorId,
            String action,
            String targetType,
            Long targetId,
            Map<String, Object> summary,
            String remoteAddress
    ) {
        return new AdminAuditEvent(
                actorId, action, targetType, targetId, summary, remoteAddress,
                AdminAuditResult.SUCCEEDED, null);
    }

    public AdminAuditEvent succeeded() {
        return new AdminAuditEvent(
                actorId, action, targetType, targetId, summary, remoteAddress,
                AdminAuditResult.SUCCEEDED, null);
    }

    public AdminAuditEvent failed(int code) {
        return new AdminAuditEvent(
                actorId, action, targetType, targetId, summary, remoteAddress,
                AdminAuditResult.FAILED, code);
    }

    public AdminAuditEvent withSummary(Map<String, Object> safeSummary) {
        return new AdminAuditEvent(
                actorId, action, targetType, targetId, safeSummary, remoteAddress,
                result, errorCode);
    }

    public AdminAuditEvent withRemoteAddress(String address) {
        return new AdminAuditEvent(
                actorId, action, targetType, targetId, summary, address, result, errorCode);
    }

    public AdminAuditEvent withTargetId(Long id) {
        return new AdminAuditEvent(
                actorId, action, targetType, id, summary, remoteAddress, result, errorCode);
    }

    private static String requiredText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new IllegalArgumentException(field + " must be nonblank and at most "
                    + maxLength + " characters");
        }
        return value.strip();
    }
}

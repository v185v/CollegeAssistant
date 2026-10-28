package xyz.v185v.collegeassistant.admin.audit;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 深层允许列表校验器，防止密钥和主机路径进入 SQL 参数。 */
@Component
public class AdminAuditRedactor {

    private static final int MAX_VALUE_LENGTH = 512;
    private static final int MAX_DEPTH = 8;
    private static final Object DROP = new Object();
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password", "token", "secret", "apikey", "objectkey");

    public AdminAuditEvent redact(AdminAuditEvent event) {
        if (event == null) {
            throw validation("Audit event is required");
        }
        return event.withSummary(redactMap(event.summary(), 0));
    }

    private Map<String, Object> redactMap(Map<?, ?> source, int depth) {
        requireDepth(depth);
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((rawKey, rawValue) -> {
            if (!(rawKey instanceof String key) || key.isBlank()) {
                throw validation("Audit summary keys must be nonblank strings");
            }
            if (isSensitiveKey(key)) {
                return;
            }
            Object value = redactValue(rawValue, depth + 1);
            if (value != DROP) {
                safe.put(key, value);
            }
        });
        return Collections.unmodifiableMap(safe);
    }

    private Object redactValue(Object value, int depth) {
        requireDepth(depth);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            if (isAbsolutePath(text)) {
                return DROP;
            }
            return bounded(text);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Enum<?> enumeration) {
            return bounded(enumeration.name());
        }
        if (value instanceof UUID || value instanceof TemporalAccessor) {
            return bounded(value.toString());
        }
        if (value instanceof Map<?, ?> map) {
            return redactMap(map, depth);
        }
        if (value instanceof Iterable<?> values) {
            List<Object> safe = new ArrayList<>();
            for (Object item : values) {
                Object redacted = redactValue(item, depth + 1);
                if (redacted != DROP) {
                    safe.add(redacted);
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(safe));
        }
        throw validation("Audit summary values must use JSON-safe scalar, map, or list types");
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private boolean isAbsolutePath(String value) {
        String candidate = value.strip();
        String lower = candidate.toLowerCase(Locale.ROOT);
        return candidate.startsWith("/")
                || candidate.startsWith("\\")
                || candidate.matches("^[A-Za-z]:[\\\\/].*")
                || lower.startsWith("file:");
    }

    private String bounded(String value) {
        if (value.length() > MAX_VALUE_LENGTH) {
            throw validation("Audit summary values must be at most 512 characters");
        }
        return value;
    }

    private void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw validation("Audit summary nesting is too deep");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}

package xyz.v185v.collegeassistant.admin.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.admin.audit.persistence.AdminAuditLogDO;
import xyz.v185v.collegeassistant.admin.audit.persistence.AdminAuditMapper;
import xyz.v185v.collegeassistant.common.PageResult;

/** 使用固定 SQL 持久化并过滤脱敏后的管理员审计事件。 */
@Repository
public class AdminAuditRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final AdminAuditMapper mapper;
    private final ObjectMapper objectMapper;

    public AdminAuditRepository(AdminAuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void insert(AdminAuditEvent event) {
        AdminAuditLogDO row = new AdminAuditLogDO();
        row.setActorId(event.actorId());
        row.setAction(event.action());
        row.setTargetType(event.targetType());
        row.setTargetId(event.targetId());
        row.setSummaryJson(serialize(event.summary()));
        row.setRemoteAddress(event.remoteAddress());
        row.setResult(event.result().name());
        row.setErrorCode(event.errorCode());
        mapper.insertEvent(row);
    }

    public PageResult<AdminAuditLogDto> find(
            AdminAuditFilter filter,
            int page,
            int size
    ) {
        long total = mapper.selectCount(auditQuery(filter));
        long offset = ((long) page - 1L) * size;
        // 保留 INET ::text 投影，使主机地址继续包含 /32 或 /128 前缀长度。
        var items = mapper.find(filter, offset, size).stream()
                .map(this::toDto)
                .toList();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(items, total, page, size, pages);
    }

    private LambdaQueryWrapper<AdminAuditLogDO> auditQuery(AdminAuditFilter filter) {
        return Wrappers.<AdminAuditLogDO>lambdaQuery()
                .eq(filter.actorId() != null, AdminAuditLogDO::getActorId, filter.actorId())
                .eq(filter.action() != null, AdminAuditLogDO::getAction, filter.action())
                .eq(filter.targetType() != null, AdminAuditLogDO::getTargetType, filter.targetType())
                .eq(filter.targetId() != null, AdminAuditLogDO::getTargetId, filter.targetId())
                .eq(filter.result() != null, AdminAuditLogDO::getResult,
                        filter.result() == null ? null : filter.result().name())
                .ge(filter.createdFrom() != null, AdminAuditLogDO::getCreatedAt, filter.createdFrom())
                .le(filter.createdTo() != null, AdminAuditLogDO::getCreatedAt, filter.createdTo());
    }

    private AdminAuditLogDto toDto(AdminAuditLogDO row) {
        return new AdminAuditLogDto(
                row.getId(),
                row.getActorId(),
                row.getAction(),
                row.getTargetType(),
                row.getTargetId(),
                parseSummary(row.getSummaryJson()),
                row.getRemoteAddress(),
                AdminAuditResult.valueOf(row.getResult()),
                row.getErrorCode(),
                row.getCreatedAt());
    }

    private String serialize(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Audit summary could not be serialized", exception);
        }
    }

    private Map<String, Object> parseSummary(String json) {
        try {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<>(objectMapper.readValue(json, JSON_OBJECT)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored audit summary is invalid", exception);
        }
    }
}

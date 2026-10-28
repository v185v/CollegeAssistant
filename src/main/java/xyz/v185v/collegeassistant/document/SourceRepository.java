package xyz.v185v.collegeassistant.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.persistence.SourceDO;
import xyz.v185v.collegeassistant.document.persistence.SourceMapper;

/** 数据源持久化，包括摄取启动和删除认领共用的记录锁。 */
@Repository
public class SourceRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SourceMapper mapper;
    private final ObjectMapper objectMapper;

    public SourceRepository(SourceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

/** 持久化完整的受管数据源配置。 */
    public SourceRecord create(SourceRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return mapSource(mapper.insertReturning(toDataObject(candidate)));
    }

/** 更新可变数据源配置，不改变删除认领状态或创建者。 */
    public Optional<SourceRecord> update(Long id, SourceRecord candidate) {
        requirePositive(id, "Source id");
        Objects.requireNonNull(candidate, "candidate");
        return Optional.ofNullable(mapper.updateReturning(id, toDataObject(candidate)))
                .map(this::mapSource);
    }

/** 返回数据源，不获取生命周期锁。 */
    public Optional<SourceRecord> findById(Long id) {
        requirePositive(id, "Source id");
        return Optional.ofNullable(mapper.selectById(id)).map(this::mapSource);
    }

/** 锁定一条数据源记录，使生命周期变更和摄取创建在此边界串行化。 */
    public Optional<SourceRecord> lockForMutation(Long id) {
        requirePositive(id, "Source id");
        return Optional.ofNullable(mapper.lockById(id)).map(this::mapSource);
    }

    /**
* 锁定到期的计划数据源，不等待其他调度器。调用方必须在持久化任务创建和下次运行时间推进期间
* 保持外围事务。
     */
    public List<SourceRecord> lockDueScheduled(OffsetDateTime now, int batchSize) {
        if (now == null) {
            throw new IllegalArgumentException("Scheduled claim time is required");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Scheduled claim batch size must be between 1 and 100");
        }
        return mapper.lockDueScheduled(now, batchSize).stream()
                .map(this::mapSource)
                .toList();
    }

/** 仅在插入匹配的计划运行记录后推进已锁定数据源。 */
    public boolean advanceScheduledRun(
            Long id,
            OffsetDateTime nextRunAt,
            OffsetDateTime claimedAt
    ) {
        requirePositive(id, "Source id");
        if (nextRunAt == null || claimedAt == null) {
            throw new IllegalArgumentException("Scheduled source timestamps are required");
        }
        return mapper.advanceScheduledRun(id, nextRunAt, claimedAt) == 1;
    }

/** 为现有摄取调用方列出已启用且未删除的数据源。 */
    public List<SourceRecord> findEnabled() {
        return mapper.selectList(Wrappers.<SourceDO>lambdaQuery()
                        .eq(SourceDO::getEnabled, true)
                        .eq(SourceDO::getDeletionStatus, "NONE")
                        .orderByAsc(SourceDO::getId))
                .stream()
                .map(this::mapSource)
                .toList();
    }

/** 分页返回完整的管理数据源清单。 */
    public PageResult<SourceRecord> find(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        Page<SourceDO> result = mapper.selectPage(
                new Page<>(page, size),
                Wrappers.<SourceDO>lambdaQuery().orderByAsc(SourceDO::getId));
        long total = result.getTotal();
        List<SourceRecord> items = result.getRecords().stream()
                .map(this::mapSource)
                .toList();
        long pages = total == 0 ? 0 : (total + size - 1) / size;
        return new PageResult<>(List.copyOf(items), total, page, size, (int) pages);
    }

/** 仅在数据源未被认领删除时改变启用状态。 */
    public Optional<SourceRecord> setEnabled(Long id, boolean enabled) {
        requirePositive(id, "Source id");
        return Optional.ofNullable(mapper.setEnabledReturning(id, enabled))
                .map(this::mapSource);
    }

/** 调用方锁定记录并检查活动运行后，认领数据源以执行删除。 */
    public boolean markDeleting(Long id) {
        requirePositive(id, "Source id");
        return mapper.markDeleting(id) == 1;
    }

/** 记录非事务删除步骤失败，同时保留数据源以便重试。 */
    public boolean markDeleteFailed(Long id) {
        requirePositive(id, "Source id");
        return mapper.markDeleteFailed(id) == 1;
    }

/** 重新认领中断的删除，使重试的持久化任务能够幂等完成。 */
    public boolean resumeDeleting(Long id) {
        requirePositive(id, "Source id");
        return mapper.resumeDeleting(id) == 1;
    }

/** 删除所有依赖的持久化状态后，移除已成功认领的数据源。 */
    public boolean deleteDeleting(Long id) {
        requirePositive(id, "Source id");
        return mapper.deleteDeleting(id) == 1;
    }

    private SourceDO toDataObject(SourceRecord candidate) {
        SourceDO row = new SourceDO();
        row.setId(candidate.id());
        row.setName(candidate.name());
        row.setBaseUrl(candidate.baseUrl());
        row.setType(candidate.type());
        row.setEnabled(candidate.enabled());
        row.setConnectorType(candidate.connectorType().name());
        row.setDeletionStatus(candidate.deletionStatus());
        row.setScheduleType(candidate.scheduleType().name());
        row.setScheduleZone(candidate.scheduleZone());
        row.setCronExpression(candidate.cronExpression());
        row.setSameHostOnly(candidate.sameHostOnly());
        row.setMaxDepth(candidate.maxDepth());
        row.setMaxPages(candidate.maxPages());
        row.setMaxConcurrency(candidate.maxConcurrency());
        row.setIncludePatternsJson(serialize(candidate.includePatterns()));
        row.setExcludePatternsJson(serialize(candidate.excludePatterns()));
        row.setNextRunAt(candidate.nextRunAt());
        row.setLastRunAt(candidate.lastRunAt());
        row.setCreatedBy(candidate.createdBy());
        row.setCreatedAt(candidate.createdAt());
        row.setUpdatedAt(candidate.updatedAt());
        return row;
    }

    private SourceRecord mapSource(SourceDO row) {
        return new SourceRecord(
                row.getId(),
                row.getName(),
                row.getBaseUrl(),
                row.getType(),
                row.getEnabled(),
                SourceConnectorType.valueOf(row.getConnectorType()),
                row.getDeletionStatus(),
                SourceScheduleType.valueOf(row.getScheduleType()),
                row.getScheduleZone(),
                row.getCronExpression(),
                row.getSameHostOnly(),
                row.getMaxDepth(),
                row.getMaxPages(),
                row.getMaxConcurrency(),
                deserialize(row.getIncludePatternsJson()),
                deserialize(row.getExcludePatternsJson()),
                row.getNextRunAt(),
                row.getLastRunAt(),
                row.getCreatedBy(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private String serialize(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Source patterns must be serializable", exception);
        }
    }

    private List<String> deserialize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored source patterns are invalid", exception);
        }
    }

    private void requirePositive(Long id, String field) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}

package xyz.v185v.collegeassistant.ingestion.persistence;

import xyz.v185v.collegeassistant.ingestion.IngestRunItemDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemStatus;
import xyz.v185v.collegeassistant.ingestion.IngestRunLease;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemDO;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemMapper;

/** 由摄取运行的尝试/令牌保护的持久化条目检查点。 */
@Repository
public class IngestRunItemRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final IngestRunItemMapper mapper;
    private final ObjectMapper objectMapper;

    public IngestRunItemRepository(IngestRunItemMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

/** 仅在给定运行尝试有效时创建稳定 URL/文件条目。 */
    public Optional<IngestRunItemDto> upsertItem(
            IngestRunLease lease,
            String itemKey,
            String itemKind
    ) {
        requireItem(itemKey, itemKind);
        return Optional.ofNullable(mapper.upsertItem(
                        lease.runId(), lease.attempt(), lease.leaseToken(), itemKey, itemKind))
                .map(this::mapItem);
    }

/** 在任何网络抓取开始前持久化规范发现条目。 */
    public Optional<IngestRunItemDto> upsertDiscovered(Long runId, String itemKey) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        requireItem(itemKey, "URL");
        return Optional.ofNullable(mapper.upsertDiscovered(runId, itemKey)).map(this::mapItem);
    }

/** 在有界 Retry-After 响应后延迟所有未完成发现条目。 */
    public int deferUnfinished(Long runId, OffsetDateTime nextAttemptAt) {
        if (runId == null || runId <= 0 || nextAttemptAt == null) {
            throw new IllegalArgumentException("Deferred item arguments are invalid");
        }
        return mapper.deferUnfinished(runId, nextAttemptAt);
    }

/** 使用与心跳和终态转换相同的 CAS 谓词写入检查点。 */
    public boolean checkpoint(
            IngestRunLease lease,
            Long itemId,
            IngestRunItemStatus status,
            Long documentId,
            Map<String, Object> checkpoint
    ) {
        if (itemId == null || itemId <= 0) {
            throw new IllegalArgumentException("itemId must be positive");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return mapper.checkpointIfLeaseActive(
                lease.runId(), lease.attempt(), lease.leaseToken(), itemId,
                status.name(), documentId, serialize(checkpoint)) == 1;
    }

    public List<IngestRunItemDto> findByRunId(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        return mapper.selectList(Wrappers.<IngestRunItemDO>lambdaQuery()
                .eq(IngestRunItemDO::getRunId, runId)
                .orderByAsc(IngestRunItemDO::getId)).stream().map(this::mapItem).toList();
    }

    private IngestRunItemDto mapItem(IngestRunItemDO row) {
        return new IngestRunItemDto(
                row.getId(),
                row.getRunId(),
                row.getItemKey(),
                row.getItemKind(),
                IngestRunItemStatus.valueOf(row.getStatus()),
                row.getDocumentId(),
                row.getRetryCount(),
                row.getNextAttemptAt(),
                row.getErrorCode(),
                row.getErrorSummary(),
                deserialize(row.getCheckpointJson()),
                row.getCheckpointAttempt(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Ingestion item checkpoint must be serializable", exception);
        }
    }

    private Map<String, Object> deserialize(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(value, JSON_OBJECT));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored ingestion item checkpoint is invalid", exception);
        }
    }

    private void requireItem(String itemKey, String itemKind) {
        if (itemKey == null || itemKey.isBlank() || itemKey.length() > 512) {
            throw new IllegalArgumentException("itemKey must be nonblank and at most 512 characters");
        }
        if (!"URL".equals(itemKind) && !"FILE".equals(itemKind)) {
            throw new IllegalArgumentException("itemKind must be URL or FILE");
        }
    }
}

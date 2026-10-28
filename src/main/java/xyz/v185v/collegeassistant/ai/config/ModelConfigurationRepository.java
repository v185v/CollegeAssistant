package xyz.v185v.collegeassistant.ai.config;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.ai.config.persistence.ModelConfigurationDO;
import xyz.v185v.collegeassistant.ai.config.persistence.ModelConfigurationMapper;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 不可变模型配置版本的固定查询持久化边界。 */
@Repository
public class ModelConfigurationRepository {

    private final ModelConfigurationMapper mapper;
    private final EmbeddingPartitionRepository partitions;

    public ModelConfigurationRepository(
            ModelConfigurationMapper mapper,
            EmbeddingPartitionRepository partitions
    ) {
        this.mapper = mapper;
        this.partitions = partitions;
    }

    public List<ModelConfiguration> findAll() {
        return List.copyOf(mapper.findAll().stream().map(this::toConfiguration).toList());
    }

    public Optional<ModelConfiguration> findById(long id) {
        requireId(id);
        return Optional.ofNullable(mapper.selectById(id)).map(this::toConfiguration);
    }

    public Optional<ModelConfiguration> lockById(long id) {
        requireId(id);
        return Optional.ofNullable(mapper.lockById(id)).map(this::toConfiguration);
    }

    public Optional<ModelConfiguration> findActive(ModelKind kind) {
        requireKind(kind);
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ModelConfigurationDO>lambdaQuery()
                .eq(ModelConfigurationDO::getModelKind, kind.name())
                .eq(ModelConfigurationDO::getStatus, "ACTIVE"))).map(this::toConfiguration);
    }

    public Optional<ModelConfiguration> findPending(ModelKind kind) {
        requireKind(kind);
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ModelConfigurationDO>lambdaQuery()
                .eq(ModelConfigurationDO::getModelKind, kind.name())
                .eq(ModelConfigurationDO::getStatus, "PENDING"))).map(this::toConfiguration);
    }

    public long reserveId() {
        return mapper.reserveId();
    }

    public ModelConfiguration insert(ModelConfiguration configuration) {
        try {
            return toConfiguration(mapper.insertReturning(toDataObject(configuration)));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "A pending model configuration already exists",
                    exception);
        }
    }

    public int retirePending(ModelKind kind) {
        requireKind(kind);
        return mapper.retirePending(kind.name());
    }

    /**
* 仅在 Embedding 分区存在后发布候选项。PostgreSQL DDL 具有事务性，
* 因此任一操作失败时都不会遗留候选记录或分区。
     */
    @Transactional
    public ModelConfiguration publishPending(ModelConfiguration configuration) {
        java.util.Objects.requireNonNull(configuration, "configuration");
        if (configuration.kind() == ModelKind.CHAT) {
            retirePending(ModelKind.CHAT);
        }
        if (configuration.kind() == ModelKind.EMBEDDING) {
            partitions.create(configuration.id());
        }
        try {
            return toConfiguration(mapper.publishPendingReturning(toDataObject(configuration)));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "A pending model configuration already exists",
                    exception);
        }
    }

    @Transactional
    public ModelConfiguration activateChat(long candidateId) {
        ModelConfiguration candidate = lockById(candidateId)
                .filter(configuration -> configuration.kind() == ModelKind.CHAT
                        && configuration.status() == ModelConfigurationStatus.PENDING)
                .orElseThrow(() -> conflict("Pending chat configuration was not found"));
        ModelConfigurationDO activated = mapper.activateChatReturning(candidate.id());
        if (activated == null) {
            throw conflict("Chat configuration activation raced with another operation");
        }
        return toConfiguration(activated);
    }

    /**
* 仅在同时锁定候选版本和当前活动版本后激活 Embedding 候选项，
* 防止两个向量空间同时处于活动状态。
     */
    @Transactional
    public ModelConfiguration activateEmbedding(long candidateId) {
        ModelConfiguration candidate = lockById(candidateId)
                .filter(configuration -> configuration.kind() == ModelKind.EMBEDDING
                        && configuration.status() == ModelConfigurationStatus.PENDING)
                .orElseThrow(() -> conflict("Pending embedding configuration was not found"));
        ModelConfigurationDO activated = mapper.activateEmbeddingReturning(candidate.id());
        if (activated == null) {
            throw conflict("Embedding configuration activation raced with another operation");
        }
        return toConfiguration(activated);
    }

/** 使一个待处理 Embedding 候选项退役，同时保留放弃原因。 */
    @Transactional
    public ModelConfiguration retirePendingEmbedding(long candidateId, String reason) {
        ModelConfiguration candidate = lockById(candidateId)
                .filter(configuration -> configuration.kind() == ModelKind.EMBEDDING
                        && configuration.status() == ModelConfigurationStatus.PENDING)
                .orElseThrow(() -> conflict("Pending embedding configuration was not found"));
        ModelConfigurationDO retired = mapper.retirePendingEmbeddingReturning(
                candidate.id(), safeReason(reason, "Embedding configuration was retired"));
        if (retired == null) {
            throw conflict("Embedding configuration retirement raced with another operation");
        }
        return toConfiguration(retired);
    }

    public boolean markFailed(long id, String reason) {
        requireId(id);
        return mapper.markFailed(
                id, safeReason(reason, "Model configuration could not be initialized")) == 1;
    }

/** 将非引导候选项标记为失败，并原子地将其隔离分区加入清理队列。 */
    @Transactional
    public boolean markEmbeddingFailedAndScheduleCleanup(long id, String reason) {
        ModelConfiguration configuration = lockById(id)
                .filter(value -> value.kind() == ModelKind.EMBEDDING)
                .filter(value -> value.status() == ModelConfigurationStatus.PENDING)
                .filter(value -> !value.isBootstrap())
                .orElse(null);
        if (configuration == null || mapper.markEmbeddingFailed(
                id, safeReason(reason, "Model configuration could not be initialized")) != 1) {
            return false;
        }
        if (mapper.insertCleanupJob(id) == null) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "An active administration job already exists");
        }
        return true;
    }

    private ModelConfigurationDO toDataObject(ModelConfiguration configuration) {
        java.util.Objects.requireNonNull(configuration, "configuration");
        ModelConfigurationDO row = new ModelConfigurationDO();
        row.setId(configuration.id());
        row.setConfigurationKey(configuration.configurationKey());
        row.setModelKind(configuration.kind().name());
        row.setProvider(configuration.provider());
        row.setModel(configuration.model());
        row.setBaseUrl(configuration.baseUrl());
        row.setTemperature(configuration.temperature());
        row.setDimension(configuration.dimension());
        row.setApiKeyCiphertext(configuration.apiKeyCiphertext());
        row.setApiKeyIv(configuration.apiKeyIv());
        row.setEncryptionKeyId(configuration.encryptionKeyId());
        row.setStatus(configuration.status().name());
        row.setCreatedBy(configuration.createdBy());
        row.setActivatedAt(configuration.activatedAt());
        row.setFailureReason(configuration.failureReason());
        row.setCreatedAt(configuration.createdAt());
        row.setUpdatedAt(configuration.updatedAt());
        return row;
    }

    private ModelConfiguration toConfiguration(ModelConfigurationDO row) {
        return new ModelConfiguration(
                row.getId(),
                row.getConfigurationKey(),
                ModelKind.valueOf(row.getModelKind()),
                row.getProvider(),
                row.getModel(),
                row.getBaseUrl(),
                row.getTemperature(),
                row.getDimension(),
                row.getApiKeyCiphertext(),
                row.getApiKeyIv(),
                row.getEncryptionKeyId(),
                ModelConfigurationStatus.valueOf(row.getStatus()),
                row.getCreatedBy(),
                row.getActivatedAt(),
                row.getFailureReason(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private void requireId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Model configuration id must be positive");
        }
    }

    private void requireKind(ModelKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("Model kind is required");
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private String safeReason(String reason, String fallback) {
        String safeReason = reason == null || reason.isBlank() ? fallback : reason.strip();
        return safeReason.length() > 1_000 ? safeReason.substring(0, 1_000) : safeReason;
    }
}

package xyz.v185v.collegeassistant.admin.model;

import java.util.Map;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobHandler;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.ai.config.EmbeddingPartitionRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 重新校验租约保护的状态后，仅删除已退役的候选分区。 */
@Component
public class IndexCleanupWorker implements AdminJobHandler {

    private final ModelConfigurationRepository configurationRepository;
    private final EmbeddingPartitionRepository partitionRepository;

    public IndexCleanupWorker(
            ModelConfigurationRepository configurationRepository,
            EmbeddingPartitionRepository partitionRepository
    ) {
        this.configurationRepository = configurationRepository;
        this.partitionRepository = partitionRepository;
    }

    @Override
    public AdminJobType type() {
        return AdminJobType.INDEX_CLEANUP;
    }

    @Override
    public void execute(AdminJobExecutionContext context) {
        long configurationId = targetConfiguration(context.job());
        AdminJobItemDto item = context.upsertItem(
                "embedding-configuration:" + configurationId,
                "EMBEDDING_CONFIGURATION",
                EmbeddingRebuildService.CONFIGURATION_TARGET_TYPE,
                configurationId);
        if (item.status() == AdminJobStatus.SUCCEEDED) {
            return;
        }
        context.inLeaseTransaction(operations -> {
            var configuration = configurationRepository.lockById(configurationId)
                    .filter(value -> value.kind() == ModelKind.EMBEDDING)
                    .filter(value -> !value.isBootstrap())
                    .filter(value -> value.status() == ModelConfigurationStatus.RETIRED
                            || value.status() == ModelConfigurationStatus.FAILED)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONFLICT,
                            "Only retired embedding configurations can be cleaned up"));
            partitionRepository.drop(configuration.id());
            operations.checkpoint(item.id(), AdminJobStatus.SUCCEEDED,
                    Map.of(
                            "stage", "PARTITION_DROPPED",
                            "embeddingConfigurationId", configurationId));
            return null;
        });
    }

    private long targetConfiguration(AdminJobDto job) {
        if (job.targetId() == null || job.targetId() <= 0
                || !EmbeddingRebuildService.CONFIGURATION_TARGET_TYPE.equals(job.targetType())) {
            throw new IllegalArgumentException("Index-cleanup jobs require an embedding configuration target");
        }
        return job.targetId();
    }
}

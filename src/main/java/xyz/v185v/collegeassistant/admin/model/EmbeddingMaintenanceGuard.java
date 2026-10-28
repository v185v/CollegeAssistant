package xyz.v185v.collegeassistant.admin.model;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 维护待处理向量空间期间阻止普通内容变更。 */
@Component
public class EmbeddingMaintenanceGuard {

    private final AdminJobRepository jobRepository;

    public EmbeddingMaintenanceGuard(AdminJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void requireWritesAllowed() {
        jobRepository.lockEmbeddingMaintenanceState();
        if (jobRepository.hasBlockingEmbeddingRebuildForPendingConfiguration()) {
            throw new BusinessException(
                    ErrorCode.EMBEDDING_REBUILD_IN_PROGRESS,
                    "Embedding maintenance window is active");
        }
    }
}

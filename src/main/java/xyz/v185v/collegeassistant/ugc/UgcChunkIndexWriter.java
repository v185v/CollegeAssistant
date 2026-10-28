package xyz.v185v.collegeassistant.ugc;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;

/** 在独立事务中校验 UGC 状态并原子替换其向量分块。 */
@Component
public class UgcChunkIndexWriter {

    private final UgcRepository ugcRepository;
    private final EmbeddingChunkRepository chunkRepository;
    private final EmbeddingMaintenanceGuard maintenanceGuard;

    @Autowired
    public UgcChunkIndexWriter(
            UgcRepository ugcRepository,
            EmbeddingChunkRepository chunkRepository,
            EmbeddingMaintenanceGuard maintenanceGuard
    ) {
        this.ugcRepository = ugcRepository;
        this.chunkRepository = chunkRepository;
        this.maintenanceGuard = maintenanceGuard;
    }

/** 为针对性写入器测试保留的兼容构造函数。 */
    public UgcChunkIndexWriter(
            UgcRepository ugcRepository,
            EmbeddingChunkRepository chunkRepository
    ) {
        this(ugcRepository, chunkRepository, null);
    }

    /**
     * 锁定帖子并仅在其仍为审核通过状态时替换索引。
     *
     * @return 已执行替换时为 {@code true}，状态已变化时为 {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean replaceIfStillApproved(
            Long postId,
            long embeddingConfigId,
            List<EmbeddingChunkRecord> records
    ) {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
        if (!ugcRepository.lockIfApproved(postId)) {
            return false;
        }
        chunkRepository.replace(
                UgcIndexingService.UGC_REFERENCE_TYPE, postId, embeddingConfigId, records);
        return true;
    }
}

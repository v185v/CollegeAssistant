package xyz.v185v.collegeassistant.admin.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.model.persistence.EmbeddingRebuildManifestItemDO;
import xyz.v185v.collegeassistant.admin.model.persistence.EmbeddingRebuildManifestMapper;

/** 一个向量重建任务的持久化快照与完成证明。 */
@Repository
public class EmbeddingRebuildManifestRepository {

    private static final int INSERT_BATCH_SIZE = 1_000;

    private final EmbeddingRebuildManifestMapper mapper;

    public EmbeddingRebuildManifestRepository(EmbeddingRebuildManifestMapper mapper) {
        this.mapper = mapper;
    }

/** 原子保存重建任务的不可变目标快照。 */
    @Transactional
    public void insertBatch(long jobId, List<EmbeddingRebuildManifestItem> items) {
        requireJobId(jobId);
        if (items == null || items.isEmpty()) {
            return;
        }

        List<EmbeddingRebuildManifestItem> snapshot = List.copyOf(items);
        Set<ManifestIdentity> identities = new HashSet<>();
        List<EmbeddingRebuildManifestItemDO> rows = new ArrayList<>(snapshot.size());
        for (EmbeddingRebuildManifestItem manifestItem : snapshot) {
            if (manifestItem.isComplete()) {
                throw new IllegalArgumentException(
                        "Embedding rebuild snapshots must not contain completion metadata");
            }
            ManifestIdentity identity = new ManifestIdentity(
                    manifestItem.targetType(), manifestItem.targetId());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Embedding rebuild manifest contains duplicate targets");
            }
            rows.add(toDataObject(jobId, manifestItem));
        }
        for (int fromIndex = 0; fromIndex < rows.size(); fromIndex += INSERT_BATCH_SIZE) {
            int toIndex = Math.min(fromIndex + INSERT_BATCH_SIZE, rows.size());
            mapper.insertBatch(jobId, rows.subList(fromIndex, toIndex));
        }
    }

    public List<EmbeddingRebuildManifestItem> findByJobId(long jobId) {
        requireJobId(jobId);
        return List.copyOf(mapper.findByJobId(jobId).stream()
                .map(this::map)
                .toList());
    }

/** 将未完成目标准确标记为完成一次，并保留其原始快照版本。 */
    public boolean markCompleted(
            long jobId,
            String targetType,
            long targetId,
            int chunkCount,
            String chunkTextChecksum
    ) {
        requireJobId(jobId);
        EmbeddingRebuildManifestItem completion = new EmbeddingRebuildManifestItem(
                targetType, targetId, "completion-validation", chunkCount, chunkTextChecksum);
        if (!completion.isComplete()) {
            throw new IllegalArgumentException("Embedding rebuild completion requires chunk metadata");
        }
        return mapper.markCompleted(
                jobId,
                completion.targetType(),
                completion.targetId(),
                completion.chunkCount(),
                completion.chunkTextChecksum()) == 1;
    }

    public long countItems(long jobId) {
        requireJobId(jobId);
        return mapper.countItems(jobId);
    }

    public long countIncomplete(long jobId) {
        requireJobId(jobId);
        return mapper.countIncomplete(jobId);
    }

    private EmbeddingRebuildManifestItemDO toDataObject(
            long jobId,
            EmbeddingRebuildManifestItem manifestItem
    ) {
        EmbeddingRebuildManifestItemDO row = new EmbeddingRebuildManifestItemDO();
        row.setJobId(jobId);
        row.setTargetType(manifestItem.targetType());
        row.setTargetId(manifestItem.targetId());
        row.setContentVersion(manifestItem.contentVersion());
        row.setChunkCount(null);
        row.setChunkTextChecksum(manifestItem.chunkTextChecksum());
        return row;
    }

    private EmbeddingRebuildManifestItem map(EmbeddingRebuildManifestItemDO row) {
        int count = row.getChunkCount() == null
                ? EmbeddingRebuildManifestItem.INCOMPLETE_CHUNK_COUNT
                : row.getChunkCount();
        return new EmbeddingRebuildManifestItem(
                row.getTargetType(),
                row.getTargetId(),
                row.getContentVersion(),
                count,
                row.getChunkTextChecksum());
    }

    private void requireJobId(long jobId) {
        if (jobId <= 0) {
            throw new IllegalArgumentException("Embedding rebuild jobId must be positive");
        }
    }

    private record ManifestIdentity(String targetType, long targetId) {
    }
}

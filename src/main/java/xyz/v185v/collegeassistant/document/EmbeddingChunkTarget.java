package xyz.v185v.collegeassistant.document;

/** 某个向量配置中至少包含一个分块的单个领域引用。 */
public record EmbeddingChunkTarget(String targetType, long targetId) {

    public EmbeddingChunkTarget {
        if (targetType == null || targetType.isBlank()) {
            throw new IllegalArgumentException("targetType must not be blank");
        }
        if (targetId <= 0) {
            throw new IllegalArgumentException("targetId must be positive");
        }
    }
}

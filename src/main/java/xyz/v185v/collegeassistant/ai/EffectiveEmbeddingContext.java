package xyz.v185v.collegeassistant.ai;

import java.util.Objects;

/** Embedding 向量空间标识与匹配模型网关组成的原子单元。 */
public record EffectiveEmbeddingContext(long configurationId, EmbeddingGateway gateway) {

    public EffectiveEmbeddingContext {
        if (configurationId <= 0) {
            throw new IllegalArgumentException("Embedding configuration id must be positive");
        }
        gateway = Objects.requireNonNull(gateway, "gateway");
    }
}

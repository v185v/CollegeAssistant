package xyz.v185v.collegeassistant.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索配置。
 *
 * @param minSimilarity 原始向量相似度的最低接受阈值
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(Double minSimilarity) {

    static final double DEFAULT_MIN_SIMILARITY = 0.35;

    public RagProperties {
        minSimilarity = minSimilarity == null ? DEFAULT_MIN_SIMILARITY : minSimilarity;
        if (!Double.isFinite(minSimilarity) || minSimilarity < 0.0 || minSimilarity > 1.0) {
            throw new IllegalArgumentException("minSimilarity must be finite and between 0 and 1");
        }
    }
}

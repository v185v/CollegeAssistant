package xyz.v185v.collegeassistant.document;

import java.util.Objects;

/**
 * 向量检索命中项。
 *
 * @param chunk 命中的分块
 * @param score 余弦相似度得分
 */
public record EmbeddingChunkSearchResult(
        EmbeddingChunkRecord chunk,
        double score,
        DocumentReferenceType documentReferenceType,
        Long documentId,
        String externalSourceUrl
) {

/** 为不加载文档引用元数据的调用方保留的兼容构造函数。 */
    public EmbeddingChunkSearchResult(EmbeddingChunkRecord chunk, double score) {
        this(chunk, score, null, null, null);
    }

    public EmbeddingChunkSearchResult {
        Objects.requireNonNull(chunk, "chunk");
        if (documentReferenceType == null
                && (documentId != null || externalSourceUrl != null)) {
            throw new IllegalArgumentException(
                    "Document citation metadata requires a document reference type");
        }
        if (documentReferenceType != null && (documentId == null || documentId <= 0)) {
            throw new IllegalArgumentException(
                    "Document citation metadata requires a positive document id");
        }
    }
}

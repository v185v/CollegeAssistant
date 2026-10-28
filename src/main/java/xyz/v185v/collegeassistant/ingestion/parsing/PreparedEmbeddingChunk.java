package xyz.v185v.collegeassistant.ingestion.parsing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;

/**
 * 摄取流水线在文档落库前准备好的向量分块。
 *
 * @param chunkIndex 分块序号
 * @param chunkText  分块正文
 * @param embedding  文本向量
 * @param sourceType 来源类型
 * @param sourceUrl  原始来源地址
 * @param title      来源标题
 * @param sourceOrg  来源机构
 * @param publishedAt 来源发布时间
 * @param degree     适用学历层次
 * @param college    适用学院
 * @param campus     适用校区
 * @param gradeRange 适用年级范围
 * @param meta       扩展元数据
 */
public record PreparedEmbeddingChunk(
        int chunkIndex,
        String chunkText,
        List<Double> embedding,
        String sourceType,
        String sourceUrl,
        String title,
        String sourceOrg,
        OffsetDateTime publishedAt,
        String degree,
        String college,
        String campus,
        String gradeRange,
        Map<String, Object> meta
) {

    public PreparedEmbeddingChunk {
        embedding = embedding == null ? null : List.copyOf(embedding);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public EmbeddingChunkRecord toRecord(Long documentId, long embeddingConfigId) {
        return new EmbeddingChunkRecord(
                null,
                embeddingConfigId,
                "DOCUMENT",
                documentId,
                chunkIndex,
                chunkText,
                embedding,
                sourceType,
                sourceUrl,
                title,
                sourceOrg,
                publishedAt,
                degree,
                college,
                campus,
                gradeRange,
                meta,
                null);
    }
}

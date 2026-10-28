package xyz.v185v.collegeassistant.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 可检索文本分块及其向量、来源和适用人群元数据。
 *
 * @param id          分块编号
 * @param docRefType  关联实体类型
 * @param docRefId    关联实体编号
 * @param chunkIndex  在原实体中的分块序号
 * @param chunkText   分块正文
 * @param embedding   固定维度向量
 * @param sourceType  来源类型
 * @param sourceUrl   原始来源地址
 * @param title       来源标题
 * @param sourceOrg   来源机构
 * @param publishedAt 来源发布时间
 * @param degree      适用学历层次
 * @param college     适用学院
 * @param campus      适用校区
 * @param gradeRange  适用年级范围
 * @param meta        扩展 JSON 元数据
 * @param createdAt   创建时间
 */
public record EmbeddingChunkRecord(
        Long id,
        long embeddingConfigId,
        String docRefType,
        Long docRefId,
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
        Map<String, Object> meta,
        OffsetDateTime createdAt
) {

    public EmbeddingChunkRecord {
        requirePositive(id, "Chunk id", true);
        if (embeddingConfigId <= 0) {
            throw new IllegalArgumentException("Chunk embeddingConfigId must be positive");
        }
        requirePositive(docRefId, "Chunk docRefId", false);
        requireText(docRefType, "Chunk docRefType");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("Chunk index must not be negative");
        }
        requireText(chunkText, "Chunk text");
        requireText(sourceType, "Chunk sourceType");
        embedding = EmbeddingVectorSupport.copyAndValidate(embedding, true);
        meta = JsonMetadataSupport.immutableCopy(meta);
    }

    private static void requirePositive(Long value, String field, boolean nullable) {
        if (value == null) {
            if (!nullable) {
                throw new IllegalArgumentException(field + " must not be null");
            }
            return;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

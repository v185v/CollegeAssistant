package xyz.v185v.collegeassistant.document;

import java.util.List;

/**
 * 向量检索条件。
 *
 * <p>画像字段为空时不参与过滤；非空时同时接受精确值和 {@code GENERAL}。
 * v1 的 {@code gradeRange} 是按相等关系匹配的单一标记，尚不解析区间表达式。</p>
 *
 * @param queryEmbedding 查询向量
 * @param topK           最大返回数量
 * @param sourceType     来源类型过滤条件
 * @param degree         学历层次过滤条件
 * @param college        学院过滤条件
 * @param campus         校区过滤条件
 * @param gradeRange     年级范围标记
 */
public record EmbeddingChunkSearchCriteria(
        long embeddingConfigId,
        List<Double> queryEmbedding,
        int topK,
        String sourceType,
        String degree,
        String college,
        String campus,
        String gradeRange
) {

    public EmbeddingChunkSearchCriteria {
        if (embeddingConfigId <= 0) {
            throw new IllegalArgumentException("embeddingConfigId must be positive");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        queryEmbedding = EmbeddingVectorSupport.copyAndValidate(queryEmbedding, true);
        rejectBlank(sourceType, "sourceType");
        rejectBlank(degree, "degree");
        rejectBlank(college, "college");
        rejectBlank(campus, "campus");
        rejectBlank(gradeRange, "gradeRange");
    }

    private static void rejectBlank(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

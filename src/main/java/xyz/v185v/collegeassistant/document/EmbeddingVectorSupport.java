package xyz.v185v.collegeassistant.document;

import java.util.List;

import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;

/** pgvector 向量的维度校验、不可变复制与文本格式转换工具。 */
final class EmbeddingVectorSupport {

    private EmbeddingVectorSupport() {
    }

    /** 校验向量维度及数值合法性，并返回不可变副本。 */
    static List<Double> copyAndValidate(List<Double> embedding, boolean rejectAllZero) {
        if (embedding == null) {
            throw new IllegalArgumentException("Embedding must not be null");
        }
        if (embedding.size() != EmbeddingDimensions.DIMENSIONS) {
            throw new IllegalArgumentException(
                    "Embedding must have exactly " + EmbeddingDimensions.DIMENSIONS + " dimensions");
        }

        boolean hasNonZeroValue = false;
        for (int index = 0; index < embedding.size(); index++) {
            Double value = embedding.get(index);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Embedding element at index " + index + " must be non-null and finite");
            }
            if (value.doubleValue() != 0.0) {
                hasNonZeroValue = true;
            }
        }
        if (rejectAllZero && !hasNonZeroValue) {
            throw new IllegalArgumentException("Embedding must not be the all-zero vector");
        }
        return List.copyOf(embedding);
    }

    /** 将向量序列化为 pgvector 接受的文本字面量。 */
    static String serialize(List<Double> embedding) {
        List<Double> validated = copyAndValidate(embedding, false);
        StringBuilder value = new StringBuilder(validated.size() * 8).append('[');
        for (int index = 0; index < validated.size(); index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(validated.get(index));
        }
        return value.append(']').toString();
    }

    /** 将数据库返回的 pgvector 文本解析为不可变数值列表。 */
    static List<Double> parse(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '['
                || value.charAt(value.length() - 1) != ']') {
            throw new IllegalStateException("Invalid pgvector value");
        }
        String[] elements = value.substring(1, value.length() - 1).split(",", -1);
        if (elements.length != EmbeddingDimensions.DIMENSIONS) {
            throw new IllegalStateException(
                    "Stored embedding must have exactly "
                            + EmbeddingDimensions.DIMENSIONS + " dimensions");
        }
        Double[] embedding = new Double[elements.length];
        try {
            for (int index = 0; index < elements.length; index++) {
                embedding[index] = Double.valueOf(elements[index]);
            }
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid numeric value in stored embedding", ex);
        }
        return copyAndValidate(List.of(embedding), false);
    }
}

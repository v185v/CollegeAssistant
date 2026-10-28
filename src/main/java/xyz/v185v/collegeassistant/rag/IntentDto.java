package xyz.v185v.collegeassistant.rag;

import java.util.Objects;

/**
 * 问题意图识别结果。
 *
 * @param type       意图类型
 * @param confidence 0 到 1 之间的置信度
 */
public record IntentDto(IntentType type, Double confidence) {

    public IntentDto {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(confidence, "confidence");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}

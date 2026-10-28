package xyz.v185v.collegeassistant.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * 流式问答请求。
 *
 * @param sessionId  可选会话编号；为空时创建新会话
 * @param question   用户问题
 * @param useMemory  是否使用会话记忆
 * @param maxSources 最大来源引用数
 */
public record ChatStreamRequest(
        @Positive
        Long sessionId,
        @NotBlank
        String question,
        Boolean useMemory,
        @Min(1) @Max(ChatConstraints.MAX_SOURCES)
        Integer maxSources
) {

    /** @return 问题按 Unicode 码点计算是否未超过接口上限 */
    @JsonIgnore
    @AssertTrue(message = "question must not exceed 1000 characters")
    public boolean isQuestionWithinCodePointLimit() {
        String normalizedQuestion = normalizeQuestion(question);
        return normalizedQuestion == null
                || normalizedQuestion.codePointCount(0, normalizedQuestion.length())
                <= ChatConstraints.MAX_QUESTION_CODE_POINTS;
    }

    /**
     * 执行规范化和跨字段校验，并填充默认值。
     *
     * @return 可直接交给编排器使用的新请求
     */
    public ChatStreamRequest validated() {
        String normalizedQuestion = normalizeQuestion(question);
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            throw validation("Question must not be blank");
        }
        if (normalizedQuestion.codePointCount(0, normalizedQuestion.length())
                > ChatConstraints.MAX_QUESTION_CODE_POINTS) {
            throw validation("Question must not exceed 1000 characters");
        }
        if (sessionId != null && sessionId <= 0) {
            throw validation("Session id must be positive");
        }
        int normalizedMaxSources = maxSources == null ? 8 : maxSources;
        if (normalizedMaxSources < 1 || normalizedMaxSources > ChatConstraints.MAX_SOURCES) {
            throw validation("maxSources must be between 1 and 20");
        }
        return new ChatStreamRequest(
                sessionId,
                normalizedQuestion,
                useMemory == null || useMemory,
                normalizedMaxSources);
    }

    private static String normalizeQuestion(String question) {
        return question == null ? null : question.strip();
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}

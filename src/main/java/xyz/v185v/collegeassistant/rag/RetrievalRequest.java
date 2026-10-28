package xyz.v185v.collegeassistant.rag;

import java.util.Objects;

import xyz.v185v.collegeassistant.profile.UserProfileDto;

/**
 * 一次双池知识检索请求。
 *
 * @param question 用户问题
 * @param profile  可选用户画像，用于硬过滤
 * @param intent   问题意图，用于双池调权
 * @param topK     最终最大返回数量
 */
public record RetrievalRequest(
        String question,
        UserProfileDto profile,
        IntentDto intent,
        int topK
) {

    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_TOP_K = 20;

    public RetrievalRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        question = question.strip();
        if (question.codePointCount(0, question.length()) > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question must not exceed 1000 characters");
        }
        Objects.requireNonNull(intent, "intent");
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
    }
}

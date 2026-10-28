package xyz.v185v.collegeassistant.rag;

import java.nio.charset.StandardCharsets;

/**
 * 发送给生成模型的提示词，显式分离可信系统策略与不可信用户/检索数据。
 *
 * @param systemPolicy 系统约束
 * @param userData     用户问题、画像和检索证据
 */
public record GenerationPrompt(String systemPolicy, String userData) {

    public static final int MAX_PROMPT_CODE_POINTS = 30_000;
    public static final int MAX_PROMPT_UTF8_BYTES = 65_536;
    private static final String INVALID_PROMPT_MESSAGE =
            "Generation prompt is invalid";

    public GenerationPrompt {
        if (systemPolicy == null || systemPolicy.isBlank()
                || userData == null || userData.isBlank()
                || exceedsLimit(systemPolicy, userData)) {
            throw new IllegalArgumentException(INVALID_PROMPT_MESSAGE);
        }
    }

    static boolean exceedsLimit(String systemPolicy, String userData) {
        return (long) codePoints(systemPolicy) + codePoints(userData)
                > MAX_PROMPT_CODE_POINTS
                || (long) utf8Bytes(systemPolicy) + utf8Bytes(userData)
                > MAX_PROMPT_UTF8_BYTES;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}

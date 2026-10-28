package xyz.v185v.collegeassistant.rag;

import java.nio.charset.StandardCharsets;

/** 对流式增量和最终答案同时实施码点数与 UTF-8 字节数上限。 */
final class AnswerBudget {

    private static final int MAX_CODE_POINTS = 20_000;
    private static final int MAX_UTF8_BYTES = 65_536;

    private int codePoints;
    private int utf8Bytes;

    /** 尝试计入一段增量文本，超出任一上限时返回 {@code false}。 */
    synchronized boolean tryAdd(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int addedCodePoints = text.codePointCount(0, text.length());
        int addedBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if ((long) codePoints + addedCodePoints > MAX_CODE_POINTS
                || (long) utf8Bytes + addedBytes > MAX_UTF8_BYTES) {
            return false;
        }
        codePoints += addedCodePoints;
        utf8Bytes += addedBytes;
        return true;
    }

    /** 校验最终答案非空且未超过响应预算。 */
    String requireValidFinal(String answer) {
        if (answer == null || answer.isBlank()
                || answer.codePointCount(0, answer.length()) > MAX_CODE_POINTS
                || answer.getBytes(StandardCharsets.UTF_8).length
                > MAX_UTF8_BYTES) {
            throw new InvalidFinalAnswerException();
        }
        return answer;
    }

    static final class InvalidFinalAnswerException extends RuntimeException {

        private InvalidFinalAnswerException() {
            super("Final answer is invalid");
        }
    }
}

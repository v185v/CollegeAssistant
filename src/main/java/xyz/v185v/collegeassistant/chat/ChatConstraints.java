package xyz.v185v.collegeassistant.chat;

/** 聊天接口、持久化和 SSE 协议共同使用的长度与数量上限。 */
public final class ChatConstraints {

    public static final int MAX_SOURCES = 20;
    public static final int MAX_QUESTION_CODE_POINTS = 1000;
    public static final int MAX_SESSION_TITLE_CODE_POINTS = 200;
    public static final int MAX_PREVIEW_CODE_POINTS = 120;
    public static final int MAX_QUOTE_CODE_POINTS = 500;
    public static final int MAX_SOURCE_TITLE_CODE_POINTS = 512;
    public static final int MAX_SOURCE_ORG_CODE_POINTS = 128;
    public static final int MAX_SOURCE_URL_CODE_POINTS = 4_096;
    public static final int MAX_SOURCES_JSON_BYTES = 128 * 1_024;
    public static final int MAX_SOURCES_JSON_STORED_BYTES = 2 * MAX_SOURCES_JSON_BYTES;

    private ChatConstraints() {
    }
}

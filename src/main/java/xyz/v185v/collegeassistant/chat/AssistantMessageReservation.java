package xyz.v185v.collegeassistant.chat;

/**
 * 为流式回答预留的助手消息标识。
 *
 * @param sessionId          会话编号
 * @param assistantMessageId 助手消息编号
 */
public record AssistantMessageReservation(
        Long sessionId,
        Long assistantMessageId
) {
}

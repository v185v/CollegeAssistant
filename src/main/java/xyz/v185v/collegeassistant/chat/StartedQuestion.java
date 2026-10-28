package xyz.v185v.collegeassistant.chat;

/**
 * 用户问题入库后的会话和消息信息。
 *
 * @param sessionId   新建或复用的会话编号
 * @param userMessage 已持久化的用户消息
 */
public record StartedQuestion(
        Long sessionId,
        ChatMessageDto userMessage
) {
}

package xyz.v185v.collegeassistant.chat;

import xyz.v185v.collegeassistant.rag.IntentDto;

/**
 * SSE 流开始时发送的会话元数据。
 *
 * @param sessionId          会话编号
 * @param assistantMessageId 预留的助手消息编号
 * @param intent             问题意图识别结果
 */
public record ChatStreamMetaDto(
        Long sessionId,
        Long assistantMessageId,
        IntentDto intent
) {
}

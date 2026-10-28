package xyz.v185v.collegeassistant.chat;

import java.util.List;

/**
 * SSE 流的最终答案事件。
 *
 * @param sessionId          会话编号
 * @param assistantMessageId 已持久化的助手消息编号
 * @param answerStatus       回答终态
 * @param answer             完整答案
 * @param sources            结构化来源引用
 */
public record ChatDoneDto(
        Long sessionId,
        Long assistantMessageId,
        AnswerStatus answerStatus,
        String answer,
        List<SourceCitationDto> sources
) {

    public ChatDoneDto {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

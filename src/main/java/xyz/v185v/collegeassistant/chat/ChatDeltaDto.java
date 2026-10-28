package xyz.v185v.collegeassistant.chat;

/**
 * SSE 流中的答案文本增量。
 *
 * @param content 本次新增文本
 */
public record ChatDeltaDto(String content) {
}

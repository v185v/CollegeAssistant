package xyz.v185v.collegeassistant.chat;

import java.time.OffsetDateTime;

/**
 * 会话列表中的摘要。
 *
 * @param id                 会话编号
 * @param title              会话标题
 * @param createdAt          创建时间
 * @param updatedAt          最近消息时间
 * @param lastMessagePreview 最后一条已完成消息预览
 */
public record ChatSessionSummaryDto(
        Long id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String lastMessagePreview
) {
}

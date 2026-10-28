package xyz.v185v.collegeassistant.chat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前用户拥有的会话详情。
 *
 * @param id        会话编号
 * @param title     会话标题
 * @param messages  已完成消息列表
 * @param hasMore   是否还有更早消息未返回
 * @param createdAt 创建时间
 * @param updatedAt 最近消息时间
 */
public record ChatSessionDetailDto(
        Long id,
        String title,
        List<ChatMessageDto> messages,
        boolean hasMore,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public ChatSessionDetailDto {
        if (messages == null || messages.isEmpty()) {
            messages = List.of();
        } else {
            List<ChatMessageDto> copy = new ArrayList<>(messages.size());
            for (ChatMessageDto message : messages) {
                if (message == null) {
                    throw new IllegalArgumentException(
                            "Chat session messages must not contain null elements");
                }
                copy.add(message);
            }
            messages = List.copyOf(copy);
        }
    }
}

package xyz.v185v.collegeassistant.chat;

/** 仅作为有界 RAG 记忆使用的轻量已完成聊天消息。 */
public record ChatMemoryMessage(
        String role,
        String content
) {

    public ChatMemoryMessage {
        if (!("USER".equals(role) || "ASSISTANT".equals(role))) {
            throw new IllegalArgumentException("Chat memory role must be USER or ASSISTANT");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Chat memory content must not be blank");
        }
    }
}

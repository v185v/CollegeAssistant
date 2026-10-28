package xyz.v185v.collegeassistant.chat.persistence;

import java.time.OffsetDateTime;

public record ChatSessionRow(
        Long id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String lastMessage
) {
}

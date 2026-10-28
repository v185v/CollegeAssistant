package xyz.v185v.collegeassistant.chat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 已完成的会话消息。
 *
 * @param id        消息编号
 * @param role      {@code USER} 或 {@code ASSISTANT}
 * @param content   消息正文
 * @param sources   助手消息引用的来源
 * @param createdAt 创建时间
 */
public record ChatMessageDto(
        Long id,
        String role,
        String content,
        List<SourceCitationDto> sources,
        OffsetDateTime createdAt
) {

    public ChatMessageDto {
        if (!"USER".equals(role) && !"ASSISTANT".equals(role)) {
            throw new IllegalArgumentException("Chat message role must be USER or ASSISTANT");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Chat message content must not be blank");
        }
        sources = immutableSources(sources);
    }

    private static List<SourceCitationDto> immutableSources(
            List<SourceCitationDto> sources
    ) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        if (sources.size() > ChatConstraints.MAX_SOURCES) {
            throw new IllegalArgumentException("Chat messages support at most 20 sources");
        }
        List<SourceCitationDto> copy = new ArrayList<>(sources.size());
        for (SourceCitationDto source : sources) {
            if (source == null) {
                throw new IllegalArgumentException("Chat sources must not contain null elements");
            }
            copy.add(source);
        }
        return List.copyOf(copy);
    }
}

package xyz.v185v.collegeassistant.chat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.chat.persistence.ChatMapper;
import xyz.v185v.collegeassistant.chat.persistence.ChatMessageDO;
import xyz.v185v.collegeassistant.chat.persistence.ChatSessionDO;
import xyz.v185v.collegeassistant.common.PageResult;

/**
 * 会话与消息仓储。
 *
 * <p>所有带用户编号的操作都在 SQL 中校验会话所有权；助手消息使用“预留空记录、
 * 终态填充或丢弃”的协议，避免流式生成失败后留下半成品答案。</p>
 */
@Repository
public class ChatRepository {

    private static final TypeReference<List<SourceCitationDto>> SOURCES_TYPE =
            new TypeReference<>() {
            };

    private final ChatMapper mapper;
    private final ObjectMapper objectMapper;

    public ChatRepository(ChatMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 创建会话并返回数据库编号。 */
    public Long createSession(Long userId, String title) {
        return mapper.createSession(userId, title);
    }

    /** 在会话属于指定用户时追加一条完整消息。 */
    public Optional<ChatMessageDto> appendOwnedMessage(
            Long userId,
            Long sessionId,
            String role,
            String content,
            List<SourceCitationDto> sources
    ) {
        requireRole(role);
        requireContent(content);
        return Optional.ofNullable(mapper.appendOwnedMessage(
                        userId,
                        sessionId,
                        role,
                        content,
                        serializeSources(sources)))
                .map(this::toDto);
    }

    /** 为指定用户拥有的会话插入空助手消息占位。 */
    public Optional<Long> reserveAssistantPlaceholder(Long userId, Long sessionId) {
        return Optional.ofNullable(mapper.reserveAssistantPlaceholder(userId, sessionId));
    }

    /**
     * 原子完成空助手消息；重复提交同一内容时返回已完成记录，保证终态操作幂等。
     */
    public Optional<ChatMessageDto> finalizeAssistantPlaceholder(
            Long userId,
            Long sessionId,
            Long messageId,
            String content,
            List<SourceCitationDto> sources
    ) {
        requireContent(content);
        String sourcesJson = serializeSources(sources);
        ChatMessageDO finalized = mapper.finalizeAssistantPlaceholder(
                userId, sessionId, messageId, content, sourcesJson);
        if (finalized != null) {
            return Optional.of(toDto(finalized));
        }
        return Optional.ofNullable(mapper.findOwnedCompletedAssistant(
                        userId, sessionId, messageId))
                .map(this::toDto);
    }

    /** 仅删除仍为空且属于指定用户会话的助手消息占位。 */
    public boolean discardAssistantPlaceholder(
            Long userId,
            Long sessionId,
            Long messageId
    ) {
        return mapper.discardAssistantPlaceholder(userId, sessionId, messageId);
    }

    /** 分批删除早于截止时间的空助手消息，并使用跳过锁避免清理器互相阻塞。 */
    public int deleteStaleAssistantPlaceholders(
            OffsetDateTime cutoff,
            int batchSize
    ) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException(
                    "Chat draft cleanup batch size must be between 1 and 1000");
        }
        return mapper.deleteStaleAssistantPlaceholders(cutoff, batchSize);
    }

    /** 分页查询指定用户的会话摘要，不把空助手占位计入最后消息。 */
    @Transactional(readOnly = true)
    public PageResult<ChatSessionSummaryDto> findOwnedSessions(
            Long userId,
            int page,
            int size
    ) {
        long total = mapper.selectCount(Wrappers.<ChatSessionDO>lambdaQuery()
                .eq(ChatSessionDO::getUserId, userId));
        long offset = calculateOffset(page, size);
        List<ChatSessionSummaryDto> items = mapper.findOwnedSessions(userId, size, offset)
                .stream()
                .map(row -> new ChatSessionSummaryDto(
                        row.id(),
                        row.title(),
                        row.createdAt(),
                        row.updatedAt(),
                        truncateCodePoints(
                                row.lastMessage(),
                                ChatConstraints.MAX_PREVIEW_CODE_POINTS)))
                .toList();
        long pageCount = total / size + (total % size == 0 ? 0 : 1);
        int pages = pageCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pageCount;
        return new PageResult<>(items, total, page, size, pages);
    }

/** 读取所属会话中近期的轻量已完成消息。 */
    @Transactional(readOnly = true)
    public Optional<List<ChatMemoryMessage>> findOwnedMemory(
            Long userId,
            Long sessionId,
            int limit
    ) {
        requireMemoryLimit(limit);
        if (!mapper.exists(Wrappers.<ChatSessionDO>lambdaQuery()
                .eq(ChatSessionDO::getUserId, userId)
                .eq(ChatSessionDO::getId, sessionId))) {
            return Optional.empty();
        }
        List<ChatMemoryMessage> messages = mapper.findOwnedMemory(userId, sessionId, limit)
                .stream()
                .map(message -> new ChatMemoryMessage(
                        message.getRole(), message.getContent()))
                .toList();
        return Optional.of(messages);
    }

    /** 查询指定用户拥有的有界会话详情，并排除未完成助手占位。 */
    @Transactional(readOnly = true)
    public Optional<ChatSessionDetailDto> findOwnedDetail(
            Long userId,
            Long sessionId,
            int limit
    ) {
        requireDetailLimit(limit);
        ChatSessionDO session = mapper.selectOne(Wrappers.<ChatSessionDO>lambdaQuery()
                .select(ChatSessionDO::getId, ChatSessionDO::getTitle,
                        ChatSessionDO::getCreatedAt, ChatSessionDO::getUpdatedAt)
                .eq(ChatSessionDO::getUserId, userId)
                .eq(ChatSessionDO::getId, sessionId));
        if (session == null) {
            return Optional.empty();
        }
        int fetchLimit = Math.addExact(limit, 1);
        List<ChatMessageDO> newestMessages = new ArrayList<>(
                mapper.findOwnedDetailMessages(userId, sessionId, fetchLimit));
        boolean hasMore = newestMessages.size() > limit;
        if (hasMore) {
            newestMessages.removeLast();
        }
        Collections.reverse(newestMessages);
        return Optional.of(new ChatSessionDetailDto(
                session.getId(),
                session.getTitle(),
                newestMessages.stream().map(this::toDto).toList(),
                hasMore,
                session.getCreatedAt(),
                session.getUpdatedAt()));
    }

    static long calculateOffset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid chat pagination");
        }
        return Math.multiplyExact((long) page - 1L, size);
    }

    private static void requireMemoryLimit(int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException(
                    "Chat memory message limit must be between 1 and 50");
        }
    }

    private static void requireDetailLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "Chat detail message limit must be between 1 and 500");
        }
    }

    String serializeSources(List<SourceCitationDto> sources) {
        List<SourceCitationDto> validated = validateSources(sources);
        requireSourcesWithinEncodingBudget(validated);
        try {
            String json = objectMapper.writeValueAsString(validated);
            if (json == null || exceedsSourcesJsonLimit(json)) {
                throw sourcesTooLarge();
            }
            return json;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to encode chat sources");
        }
    }

    List<SourceCitationDto> deserializeSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        if (exceedsStoredSourcesJsonLimit(json)) {
            throw new IllegalStateException("Failed to decode chat sources");
        }
        try {
            List<SourceCitationDto> decoded = objectMapper.readValue(json, SOURCES_TYPE);
            List<SourceCitationDto> validated = validateSources(decoded);
            serializeSources(validated);
            return validated;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to decode chat sources");
        }
    }

    private ChatMessageDto toDto(ChatMessageDO message) {
        return new ChatMessageDto(
                message.getId(),
                message.getRole(),
                message.getContent(),
                deserializeSources(message.getSourcesJson()),
                message.getCreatedAt());
    }

    private List<SourceCitationDto> validateSources(List<SourceCitationDto> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        if (sources.size() > ChatConstraints.MAX_SOURCES) {
            throw new IllegalArgumentException("Chat messages support at most 20 sources");
        }
        List<SourceCitationDto> copy = new ArrayList<>(sources.size());
        for (SourceCitationDto source : sources) {
            if (source == null) {
                throw new IllegalArgumentException(
                        "Chat sources must not contain null elements");
            }
            copy.add(source);
        }
        return List.copyOf(copy);
    }

    private void requireSourcesWithinEncodingBudget(
            List<SourceCitationDto> sources
    ) {
        long minimumJsonBytes = 2L;
        for (SourceCitationDto source : sources) {
            minimumJsonBytes += utf8Length(source.title());
            minimumJsonBytes += utf8Length(source.sourceUrl());
            minimumJsonBytes += utf8Length(source.sourceOrg());
            minimumJsonBytes += utf8Length(source.quote());
            if (minimumJsonBytes > ChatConstraints.MAX_SOURCES_JSON_BYTES) {
                throw sourcesTooLarge();
            }
        }
    }

    private boolean exceedsSourcesJsonLimit(String value) {
        if (value.length() > ChatConstraints.MAX_SOURCES_JSON_BYTES) {
            return true;
        }
        return utf8Length(value) > ChatConstraints.MAX_SOURCES_JSON_BYTES;
    }

    private boolean exceedsStoredSourcesJsonLimit(String value) {
        if (value.length() > ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES) {
            return true;
        }
        return utf8Length(value) > ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES;
    }

    private long utf8Length(String value) {
        return value == null
                ? 0L
                : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private IllegalArgumentException sourcesTooLarge() {
        return new IllegalArgumentException(
                "Chat sources JSON exceeds the size limit");
    }

    private void requireRole(String role) {
        if (!"USER".equals(role) && !"ASSISTANT".equals(role)) {
            throw new IllegalArgumentException(
                    "Chat message role must be USER or ASSISTANT");
        }
    }

    private void requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "Chat message content must not be blank");
        }
    }

    static String truncateCodePoints(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        if (maxCodePoints < 0) {
            throw new IllegalArgumentException(
                    "Maximum code points must not be negative");
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}

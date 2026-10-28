package xyz.v185v.collegeassistant.chat.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ChatMapper extends BaseMapper<ChatSessionDO> {

    Long createSession(@Param("userId") Long userId, @Param("title") String title);

    ChatMessageDO appendOwnedMessage(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("sources") String sourcesJson);

    Long reserveAssistantPlaceholder(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId);

    ChatMessageDO finalizeAssistantPlaceholder(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("messageId") Long messageId,
            @Param("content") String content,
            @Param("sources") String sourcesJson);

    boolean discardAssistantPlaceholder(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("messageId") Long messageId);

    int deleteStaleAssistantPlaceholders(
            @Param("cutoff") OffsetDateTime cutoff,
            @Param("batchSize") int batchSize);

    List<ChatSessionRow> findOwnedSessions(
            @Param("userId") Long userId,
            @Param("size") int size,
            @Param("offset") long offset);

    List<ChatMessageDO> findOwnedMemory(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("limit") int limit);

    List<ChatMessageDO> findOwnedDetailMessages(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("fetchLimit") int fetchLimit);

    ChatMessageDO findOwnedCompletedAssistant(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("messageId") Long messageId);
}

package xyz.v185v.collegeassistant.chat;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

/**
 * 聊天会话应用服务，负责所有权校验、输入约束和助手消息占位生命周期。
 */
@Service
public class ChatService {

    private static final String USER_ROLE = "USER";

    private final ChatRepository repository;
    private final ChatProperties properties;

    public ChatService(ChatRepository repository, ChatProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 新建或复用会话并保存用户问题。
     *
     * @return 会话编号及已保存的用户消息
     */
    @Transactional
    public StartedQuestion startQuestion(
            AuthPrincipal principal,
            Long sessionId,
            String question
    ) {
        Long userId = userId(principal);
        requireQuestion(question);
        Long targetSessionId = sessionId;
        if (targetSessionId == null) {
            targetSessionId = repository.createSession(userId, title(question));
        } else {
            requirePositiveSession(targetSessionId);
        }
        ChatMessageDto message = repository.appendOwnedMessage(
                        userId, targetSessionId, USER_ROLE, question, List.of())
                .orElseThrow(this::sessionNotFound);
        return new StartedQuestion(targetSessionId, message);
    }

    /** 一次性保存助手消息；内部仍通过占位记录保证写入协议一致。 */
    @Transactional
    public ChatMessageDto saveAssistantMessage(
            AuthPrincipal principal,
            Long sessionId,
            String content,
            List<SourceCitationDto> sources
    ) {
        Long userId = userId(principal);
        requirePositiveSession(sessionId);
        List<SourceCitationDto> safeSources = requireAssistantContent(content, sources);
        Long messageId = repository.reserveAssistantPlaceholder(userId, sessionId)
                .orElseThrow(this::sessionNotFound);
        return repository.finalizeAssistantPlaceholder(
                        userId, sessionId, messageId, content, safeSources)
                .orElseThrow(this::sessionNotFound);
    }

    /** 为即将开始的流式回答预留空助手消息。 */
    @Transactional
    public AssistantMessageReservation reserveAssistantMessage(
            AuthPrincipal principal,
            Long sessionId
    ) {
        Long userId = userId(principal);
        requirePositiveSession(sessionId);
        Long messageId = repository.reserveAssistantPlaceholder(userId, sessionId)
                .orElseThrow(this::sessionNotFound);
        return new AssistantMessageReservation(sessionId, messageId);
    }

    /** 将当前用户拥有的空占位消息原子更新为完整回答。 */
    @Transactional
    public ChatMessageDto finalizeAssistantMessage(
            AuthPrincipal principal,
            Long sessionId,
            Long messageId,
            String content,
            List<SourceCitationDto> sources
    ) {
        Long userId = userId(principal);
        requirePositiveSession(sessionId);
        if (messageId == null || messageId <= 0) {
            throw sessionNotFound();
        }
        List<SourceCitationDto> safeSources = requireAssistantContent(content, sources);
        return repository.finalizeAssistantPlaceholder(
                        userId, sessionId, messageId, content, safeSources)
                .orElseThrow(this::sessionNotFound);
    }

    /** 在取消或失败时删除仍为空的助手消息占位。 */
    @Transactional
    public void discardAssistantPlaceholder(
            AuthPrincipal principal,
            Long sessionId,
            Long messageId
    ) {
        Long userId = userId(principal);
        requirePositiveSession(sessionId);
        if (messageId == null || messageId <= 0) {
            return;
        }
        repository.discardAssistantPlaceholder(userId, sessionId, messageId);
    }

    /** 分页查询当前用户的会话摘要。 */
    public PageResult<ChatSessionSummaryDto> listSessions(
            AuthPrincipal principal,
            int page,
            int size
    ) {
        Long userId = userId(principal);
        requirePage(page, size);
        return repository.findOwnedSessions(userId, page, size);
    }

    /** 查询当前用户拥有的会话详情。 */
    public ChatSessionDetailDto getSession(AuthPrincipal principal, Long sessionId) {
        Long userId = userId(principal);
        requirePositiveSession(sessionId);
        return repository.findOwnedDetail(
                        userId, sessionId, properties.detailMessageLimit())
                .orElseThrow(this::sessionNotFound);
    }

/** 返回有界轻量历史供 RAG 使用，不加载引用信息。 */
    public List<ChatMemoryMessage> getMemory(
            AuthPrincipal principal,
            Long sessionId
    ) {
        Long userId = userId(principal);
        requirePositiveSession(sessionId);
        return repository.findOwnedMemory(
                        userId, sessionId, properties.memoryMessageLimit())
                .orElseThrow(this::sessionNotFound);
    }

    private List<SourceCitationDto> requireAssistantContent(
            String content,
            List<SourceCitationDto> sources
    ) {
        if (content == null || content.isBlank()) {
            throw validation("Assistant content must not be blank");
        }
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        if (sources.size() > ChatConstraints.MAX_SOURCES) {
            throw validation("Assistant messages support at most 20 sources");
        }
        List<SourceCitationDto> copy = new ArrayList<>(sources.size());
        for (SourceCitationDto source : sources) {
            if (source == null) {
                throw validation("Assistant sources must not contain null elements");
            }
            copy.add(source);
        }
        return List.copyOf(copy);
    }

    private void requireQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw validation("Question must not be blank");
        }
        if (question.codePointCount(0, question.length())
                > ChatConstraints.MAX_QUESTION_CODE_POINTS) {
            throw validation("Question must not exceed 1000 characters");
        }
    }

    private void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw validation("Page must be positive and size must be between 1 and 100");
        }
    }

    private void requirePositiveSession(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw sessionNotFound();
        }
    }

    private Long userId(AuthPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.userId() <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }

    private String title(String question) {
        String stripped = question.strip();
        int codePoints = stripped.codePointCount(0, stripped.length());
        if (codePoints <= ChatConstraints.MAX_SESSION_TITLE_CODE_POINTS) {
            return stripped;
        }
        int end = stripped.offsetByCodePoints(
                0, ChatConstraints.MAX_SESSION_TITLE_CODE_POINTS);
        return stripped.substring(0, end);
    }

    private BusinessException sessionNotFound() {
        return new BusinessException(
                ErrorCode.CHAT_SESSION_NOT_FOUND, "Chat session not found");
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}

package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class ChatRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ChatRepository repository;

    @Autowired
    private ChatService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void createsSessionAndUserMessageWhileHidingExistenceFromAnotherOwner() {
        AuthPrincipal alice = principal(insertUser("chat-alice"), "chat-alice");
        AuthPrincipal bob = principal(insertUser("chat-bob"), "chat-bob");

        StartedQuestion started = service.startQuestion(alice, null, "How is GPA calculated?");

        assertThat(started.sessionId()).isPositive();
        assertThat(started.userMessage().role()).isEqualTo("USER");
        assertThat(started.userMessage().content()).isEqualTo("How is GPA calculated?");
        assertNotFound(() -> service.getSession(bob, started.sessionId()));
        assertNotFound(() -> service.startQuestion(bob, started.sessionId(), "Steal session"));
        assertThat(messageCount(started.sessionId())).isEqualTo(1);
    }

    @Test
    void placeholderIsHiddenThenDuplicateFinalizeReturnsOriginalWithoutCrossOwnerAccess() {
        AuthPrincipal alice = principal(insertUser("stream-alice"), "stream-alice");
        AuthPrincipal bob = principal(insertUser("stream-bob"), "stream-bob");
        StartedQuestion started = service.startQuestion(alice, null, "Question");
        AssistantMessageReservation reservation =
                service.reserveAssistantMessage(alice, started.sessionId());

        assertThat(service.getSession(alice, started.sessionId()).messages())
                .extracting(ChatMessageDto::role)
                .containsExactly("USER");
        assertThat(rawMessageContent(reservation.assistantMessageId())).isEmpty();
        assertNotFound(() -> service.finalizeAssistantMessage(
                bob,
                started.sessionId(),
                reservation.assistantMessageId(),
                "Cross-owner overwrite",
                List.of()));

        List<SourceCitationDto> sources = List.of(officialCitation(), ugcCitation());
        ChatMessageDto finalized = service.finalizeAssistantMessage(
                alice,
                started.sessionId(),
                reservation.assistantMessageId(),
                "Final answer",
                sources);

        assertThat(finalized.sources()).isEqualTo(sources);
        assertThat(service.getSession(alice, started.sessionId()).messages())
                .extracting(ChatMessageDto::content)
                .containsExactly("Question", "Final answer");
        ChatMessageDto duplicate = service.finalizeAssistantMessage(
                alice,
                started.sessionId(),
                reservation.assistantMessageId(),
                "Duplicate overwrite",
                List.of());
        assertThat(duplicate.content()).isEqualTo("Final answer");
        assertThat(duplicate.sources()).isEqualTo(sources);
        assertThat(rawMessageContent(reservation.assistantMessageId()))
                .isEqualTo("Final answer");
        assertNotFound(() -> service.finalizeAssistantMessage(
                bob,
                started.sessionId(),
                reservation.assistantMessageId(),
                "Cross-owner duplicate",
                List.of()));

        AssistantMessageReservation failed =
                service.reserveAssistantMessage(alice, started.sessionId());
        service.discardAssistantPlaceholder(
                alice, failed.sessionId(), failed.assistantMessageId());
        service.discardAssistantPlaceholder(
                alice, failed.sessionId(), failed.assistantMessageId());
        assertThat(messageExists(failed.assistantMessageId())).isFalse();
    }

    @Test
    void listUsesStableUpdatedOrderingAndUnicodeSafeLastMessagePreview() {
        AuthPrincipal alice = principal(insertUser("list-alice"), "list-alice");
        StartedQuestion first = service.startQuestion(alice, null, "First");
        StartedQuestion second = service.startQuestion(alice, null, "😀".repeat(130));
        StartedQuestion third = service.startQuestion(alice, null, "Third");
        Long empty = repository.createSession(alice.userId(), "Empty");
        setUpdatedAt(first.sessionId(), "2026-07-14T09:00:00Z");
        setUpdatedAt(second.sessionId(), "2026-07-14T10:00:00Z");
        setUpdatedAt(third.sessionId(), "2026-07-14T10:00:00Z");
        setUpdatedAt(empty, "2026-07-14T11:00:00Z");

        PageResult<ChatSessionSummaryDto> page = service.listSessions(alice, 1, 10);

        assertThat(page.items()).extracting(ChatSessionSummaryDto::id)
                .containsExactly(empty, third.sessionId(), second.sessionId(), first.sessionId());
        assertThat(page.items().getFirst().lastMessagePreview()).isNull();
        String preview = page.items().get(2).lastMessagePreview();
        assertThat(preview).isEqualTo("😀".repeat(120));
        assertThat(Character.isHighSurrogate(preview.charAt(preview.length() - 1))).isFalse();
    }

    @Test
    void memoryAndDetailQueriesAreBoundedStableOwnerScopedAndHidePlaceholders() {
        AuthPrincipal alice = principal(insertUser("bounded-alice"), "bounded-alice");
        AuthPrincipal bob = principal(insertUser("bounded-bob"), "bounded-bob");
        Long sessionId = repository.createSession(alice.userId(), "Bounded history");
        String timestamp = "2026-07-14T12:00:00Z";
        for (int index = 1; index <= 205; index++) {
            insertRawMessage(
                    sessionId,
                    index % 2 == 0 ? "ASSISTANT" : "USER",
                    "message-" + index,
                    timestamp);
        }
        insertRawMessage(sessionId, "ASSISTANT", "", timestamp);
        Long bobSession = repository.createSession(bob.userId(), "Bob history");
        insertRawMessage(bobSession, "USER", "bob-only", timestamp);

        List<ChatMemoryMessage> memory = repository
                .findOwnedMemory(alice.userId(), sessionId, 8)
                .orElseThrow();
        ChatSessionDetailDto detail = repository
                .findOwnedDetail(alice.userId(), sessionId, 200)
                .orElseThrow();

        assertThat(memory).extracting(ChatMemoryMessage::content)
                .containsExactly(
                        "message-198", "message-199", "message-200", "message-201",
                        "message-202", "message-203", "message-204", "message-205");
        assertThat(detail.hasMore()).isTrue();
        assertThat(detail.messages()).hasSize(200);
        assertThat(detail.messages().getFirst().content()).isEqualTo("message-6");
        assertThat(detail.messages().getLast().content()).isEqualTo("message-205");
        assertThat(detail.messages()).extracting(ChatMessageDto::content)
                .doesNotContain("", "bob-only");
        assertThat(repository.findOwnedMemory(bob.userId(), sessionId, 8)).isEmpty();
        assertThat(repository.findOwnedDetail(bob.userId(), sessionId, 200)).isEmpty();
    }

    @Test
    void visibleWritesNeverMoveFutureSessionActivityBackwardAndDraftLifecycleDoesNotTouchIt() {
        AuthPrincipal alice = principal(insertUser("touch-alice"), "touch-alice");
        StartedQuestion started = service.startQuestion(alice, null, "Question");
        OffsetDateTime future = OffsetDateTime.parse("2099-01-01T00:00:00Z");
        setUpdatedAt(started.sessionId(), future.toString());

        service.startQuestion(alice, started.sessionId(), "Second question");
        assertThat(updatedAt(started.sessionId())).isEqualTo(future);

        AssistantMessageReservation reservation =
                service.reserveAssistantMessage(alice, started.sessionId());
        assertThat(updatedAt(started.sessionId())).isEqualTo(future);

        service.finalizeAssistantMessage(
                alice, started.sessionId(), reservation.assistantMessageId(), "Answer", List.of());
        assertThat(updatedAt(started.sessionId())).isEqualTo(future);

        AssistantMessageReservation failed =
                service.reserveAssistantMessage(alice, started.sessionId());
        assertThat(updatedAt(started.sessionId())).isEqualTo(future);
        service.discardAssistantPlaceholder(
                alice, started.sessionId(), failed.assistantMessageId());
        assertThat(updatedAt(started.sessionId())).isEqualTo(future);
    }

    @Test
    void staleCleanupDeletesOnlyOldEmptyAssistantsAndDoesNotTouchSessionActivity() {
        AuthPrincipal alice = principal(insertUser("cleanup-alice"), "cleanup-alice");
        StartedQuestion started = service.startQuestion(alice, null, "Visible question");
        AssistantMessageReservation oldDraft =
                service.reserveAssistantMessage(alice, started.sessionId());
        AssistantMessageReservation recentDraft =
                service.reserveAssistantMessage(alice, started.sessionId());
        AssistantMessageReservation visibleAssistant =
                service.reserveAssistantMessage(alice, started.sessionId());
        service.finalizeAssistantMessage(
                alice,
                started.sessionId(),
                visibleAssistant.assistantMessageId(),
                "Visible answer",
                List.of());
        setMessageCreatedAt(oldDraft.assistantMessageId(), "2026-07-14T09:00:00Z");
        setMessageCreatedAt(recentDraft.assistantMessageId(), "2026-07-14T11:30:00Z");
        setMessageCreatedAt(visibleAssistant.assistantMessageId(), "2026-07-14T09:00:00Z");
        setMessageCreatedAt(started.userMessage().id(), "2026-07-14T09:00:00Z");
        OffsetDateTime activity = OffsetDateTime.parse("2026-07-14T10:30:00Z");
        setUpdatedAt(started.sessionId(), activity.toString());

        int deleted = repository.deleteStaleAssistantPlaceholders(
                OffsetDateTime.parse("2026-07-14T11:00:00Z"), 10);

        assertThat(deleted).isEqualTo(1);
        assertThat(messageExists(oldDraft.assistantMessageId())).isFalse();
        assertThat(messageExists(recentDraft.assistantMessageId())).isTrue();
        assertThat(messageExists(visibleAssistant.assistantMessageId())).isTrue();
        assertThat(messageExists(started.userMessage().id())).isTrue();
        assertThat(updatedAt(started.sessionId())).isEqualTo(activity);
    }

    @Test
    void outerRollbackRemovesNewSessionAndUserMessageAndRestoresPlaceholderFinalize() {
        AuthPrincipal alice = principal(insertUser("rollback-alice"), "rollback-alice");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        AtomicReference<Long> rolledBackSession = new AtomicReference<>();

        transactions.executeWithoutResult(status -> {
            rolledBackSession.set(service.startQuestion(alice, null, "Rolled back").sessionId());
            status.setRollbackOnly();
        });
        assertThat(sessionExists(rolledBackSession.get())).isFalse();

        StartedQuestion started = service.startQuestion(alice, null, "Retained question");
        AssistantMessageReservation reservation =
                service.reserveAssistantMessage(alice, started.sessionId());
        transactions.executeWithoutResult(status -> {
            service.finalizeAssistantMessage(
                    alice,
                    started.sessionId(),
                    reservation.assistantMessageId(),
                    "Rolled-back answer",
                    List.of());
            status.setRollbackOnly();
        });
        assertThat(rawMessageContent(reservation.assistantMessageId())).isEmpty();
    }

    private Long insertUser(String username) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, 'hash', :username, 'STUDENT')
                        RETURNING id
                        """)
                .param("username", username)
                .query(Long.class)
                .single();
    }

    private AuthPrincipal principal(Long id, String username) {
        return new AuthPrincipal(id, username, UserRole.STUDENT.name());
    }

    private SourceCitationDto officialCitation() {
        return new SourceCitationDto(
                301L,
                SourceType.OFFICIAL,
                "Rules",
                "https://example.edu/rules",
                "Registrar",
                OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                0.87,
                "Quote");
    }

    private SourceCitationDto ugcCitation() {
        return new SourceCitationDto(
                302L, SourceType.UGC, null, null, null, null, null, null);
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    private long messageCount(Long sessionId) {
        return jdbcClient.sql("SELECT count(*) FROM chat_message WHERE session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
    }

    private String rawMessageContent(Long messageId) {
        return jdbcClient.sql("SELECT content FROM chat_message WHERE id = :messageId")
                .param("messageId", messageId)
                .query(String.class)
                .single();
    }

    private boolean messageExists(Long messageId) {
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM chat_message WHERE id = :messageId)")
                .param("messageId", messageId)
                .query(Boolean.class)
                .single();
    }

    private boolean sessionExists(Long sessionId) {
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM chat_session WHERE id = :sessionId)")
                .param("sessionId", sessionId)
                .query(Boolean.class)
                .single();
    }

    private void setUpdatedAt(Long sessionId, String value) {
        jdbcClient.sql("""
                        UPDATE chat_session
                        SET updated_at = CAST(:value AS timestamptz)
                        WHERE id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .param("value", value)
                .update();
    }

    private void setMessageCreatedAt(Long messageId, String value) {
        jdbcClient.sql("""
                        UPDATE chat_message
                        SET created_at = CAST(:value AS timestamptz)
                        WHERE id = :messageId
                        """)
                .param("messageId", messageId)
                .param("value", value)
                .update();
    }

    private void insertRawMessage(
            Long sessionId,
            String role,
            String content,
            String createdAt
    ) {
        jdbcClient.sql("""
                        INSERT INTO chat_message (
                            session_id, role, content, sources, created_at
                        )
                        VALUES (
                            :sessionId, :role, :content, '[]'::jsonb,
                            CAST(:createdAt AS timestamptz)
                        )
                        """)
                .param("sessionId", sessionId)
                .param("role", role)
                .param("content", content)
                .param("createdAt", createdAt)
                .update();
    }

    private OffsetDateTime updatedAt(Long sessionId) {
        return jdbcClient.sql("SELECT updated_at FROM chat_session WHERE id = :sessionId")
                .param("sessionId", sessionId)
                .query(OffsetDateTime.class)
                .single();
    }
}

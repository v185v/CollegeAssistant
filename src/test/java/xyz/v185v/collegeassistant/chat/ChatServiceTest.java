package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final AuthPrincipal ALICE =
            new AuthPrincipal(7L, "alice", UserRole.STUDENT.name());

    @Mock
    private ChatRepository repository;

    @Mock
    private ChatProperties properties;

    private ChatService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(properties.memoryMessageLimit()).thenReturn(8);
        org.mockito.Mockito.lenient().when(properties.detailMessageLimit()).thenReturn(200);
        service = new ChatService(repository, properties);
    }

    @Test
    void questionWithoutSessionCreatesSessionAndUserMessageAtomically() {
        String question = "😀".repeat(205);
        String title = "😀".repeat(200);
        ChatMessageDto userMessage = message(101L, "USER", question, List.of());
        when(repository.createSession(ALICE.userId(), title)).thenReturn(31L);
        when(repository.appendOwnedMessage(
                ALICE.userId(), 31L, "USER", question, List.of()))
                .thenReturn(Optional.of(userMessage));

        StartedQuestion result = service.startQuestion(ALICE, null, question);

        assertThat(result.sessionId()).isEqualTo(31L);
        assertThat(result.userMessage()).isEqualTo(userMessage);
        assertThat(title.codePointCount(0, title.length())).isEqualTo(200);
        assertThat(Character.isHighSurrogate(title.charAt(title.length() - 1))).isFalse();
        InOrder order = inOrder(repository);
        order.verify(repository).createSession(ALICE.userId(), title);
        order.verify(repository).appendOwnedMessage(
                ALICE.userId(), 31L, "USER", question, List.of());
    }

    @Test
    void existingOwnedSessionSavesUserMessageWithoutCreatingAnotherSession() {
        ChatMessageDto userMessage = message(102L, "USER", "What is GPA?", List.of());
        when(repository.appendOwnedMessage(
                ALICE.userId(), 31L, "USER", "What is GPA?", List.of()))
                .thenReturn(Optional.of(userMessage));

        StartedQuestion result = service.startQuestion(ALICE, 31L, "What is GPA?");

        assertThat(result).isEqualTo(new StartedQuestion(31L, userMessage));
        verify(repository, never()).createSession(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void assistantAnswerIsReservedThenFinalizedWithSourcesInOneConvenienceTransaction() {
        List<SourceCitationDto> sources = List.of(citation());
        ChatMessageDto assistant = message(201L, "ASSISTANT", "Policy answer", sources);
        when(repository.reserveAssistantPlaceholder(ALICE.userId(), 31L))
                .thenReturn(Optional.of(201L));
        when(repository.finalizeAssistantPlaceholder(
                ALICE.userId(), 31L, 201L, "Policy answer", sources))
                .thenReturn(Optional.of(assistant));

        ChatMessageDto result = service.saveAssistantMessage(
                ALICE, 31L, "Policy answer", sources);

        assertThat(result).isEqualTo(assistant);
        InOrder order = inOrder(repository);
        order.verify(repository).reserveAssistantPlaceholder(ALICE.userId(), 31L);
        order.verify(repository).finalizeAssistantPlaceholder(
                ALICE.userId(), 31L, 201L, "Policy answer", sources);
    }

    @Test
    void duplicateStreamingFinalizeReturnsTheAlreadyPersistedMessage() {
        when(repository.reserveAssistantPlaceholder(ALICE.userId(), 31L))
                .thenReturn(Optional.of(202L));
        ChatMessageDto finalized = message(202L, "ASSISTANT", "Done", List.of());
        when(repository.finalizeAssistantPlaceholder(
                ALICE.userId(), 31L, 202L, "Done", List.of()))
                .thenReturn(Optional.of(finalized));
        when(repository.finalizeAssistantPlaceholder(
                ALICE.userId(), 31L, 202L, "Overwrite", List.of()))
                .thenReturn(Optional.of(finalized));

        AssistantMessageReservation reservation =
                service.reserveAssistantMessage(ALICE, 31L);
        ChatMessageDto result = service.finalizeAssistantMessage(
                ALICE, reservation.sessionId(), reservation.assistantMessageId(), "Done", List.of());

        assertThat(reservation).isEqualTo(new AssistantMessageReservation(31L, 202L));
        assertThat(result).isEqualTo(finalized);
        ChatMessageDto duplicate = service.finalizeAssistantMessage(
                ALICE, 31L, 202L, "Overwrite", List.of());

        assertThat(duplicate).isEqualTo(finalized);
        assertThat(duplicate.content()).isEqualTo("Done");
    }

    @Test
    void failedStreamCanIdempotentlyDiscardOnlyItsOwnedEmptyPlaceholder() {
        when(repository.discardAssistantPlaceholder(ALICE.userId(), 31L, 203L))
                .thenReturn(true, false);

        service.discardAssistantPlaceholder(ALICE, 31L, 203L);
        service.discardAssistantPlaceholder(ALICE, 31L, 203L);

        verify(repository, org.mockito.Mockito.times(2))
                .discardAssistantPlaceholder(ALICE.userId(), 31L, 203L);
    }

    @Test
    void ownershipFailuresForReadAndEveryWriteUseTheSameNotFoundCode() {
        when(repository.findOwnedDetail(ALICE.userId(), 99L, 200)).thenReturn(Optional.empty());
        when(repository.findOwnedMemory(ALICE.userId(), 99L, 8)).thenReturn(Optional.empty());
        when(repository.appendOwnedMessage(
                ALICE.userId(), 99L, "USER", "Question", List.of()))
                .thenReturn(Optional.empty());
        when(repository.reserveAssistantPlaceholder(ALICE.userId(), 99L))
                .thenReturn(Optional.empty());
        when(repository.finalizeAssistantPlaceholder(
                ALICE.userId(), 99L, 8L, "Answer", List.of()))
                .thenReturn(Optional.empty());

        assertNotFound(() -> service.getSession(ALICE, 99L));
        assertNotFound(() -> service.getMemory(ALICE, 99L));
        assertNotFound(() -> service.startQuestion(ALICE, 99L, "Question"));
        assertNotFound(() -> service.reserveAssistantMessage(ALICE, 99L));
        assertNotFound(() -> service.finalizeAssistantMessage(
                ALICE, 99L, 8L, "Answer", List.of()));
    }

    @Test
    void listDetailAndLightweightMemoryDelegateWithConfiguredLimits() {
        ChatSessionSummaryDto summary = new ChatSessionSummaryDto(
                31L, "GPA", time(), time(), "Last answer");
        PageResult<ChatSessionSummaryDto> page =
                new PageResult<>(List.of(summary), 1, 2, 10, 1);
        when(repository.findOwnedSessions(ALICE.userId(), 2, 10)).thenReturn(page);
        ChatSessionDetailDto detail = new ChatSessionDetailDto(
                31L,
                "GPA",
                List.of(message(1L, "USER", "Question", List.of())),
                false,
                time(),
                time());
        List<ChatMemoryMessage> memory = List.of(
                new ChatMemoryMessage("USER", "Question"));
        when(repository.findOwnedDetail(ALICE.userId(), 31L, 200)).thenReturn(Optional.of(detail));
        when(repository.findOwnedMemory(ALICE.userId(), 31L, 8)).thenReturn(Optional.of(memory));

        assertThat(service.listSessions(ALICE, 2, 10)).isSameAs(page);
        assertThat(service.getSession(ALICE, 31L)).isSameAs(detail);
        assertThat(service.getMemory(ALICE, 31L)).isSameAs(memory);
    }

    @Test
    void questionContentPagingAndSourcesAreValidatedBeforeRepositoryAccess() {
        assertValidation(() -> service.startQuestion(ALICE, null, "  "));
        assertValidation(() -> service.startQuestion(ALICE, null, "问".repeat(1001)));
        assertValidation(() -> service.listSessions(ALICE, 0, 20));
        assertValidation(() -> service.listSessions(ALICE, 1, 0));
        assertValidation(() -> service.listSessions(ALICE, 1, 101));
        assertValidation(() -> service.saveAssistantMessage(ALICE, 31L, " ", List.of()));
        assertValidation(() -> service.saveAssistantMessage(
                ALICE, 31L, "Answer", Collections.nCopies(21, citation())));

        verifyNoInteractions(repository);
    }

    @Test
    void persistenceWorkflowsExposeTransactionalBoundaries() throws Exception {
        assertTransactional("startQuestion", AuthPrincipal.class, Long.class, String.class);
        assertTransactional("saveAssistantMessage",
                AuthPrincipal.class, Long.class, String.class, List.class);
        assertTransactional("reserveAssistantMessage", AuthPrincipal.class, Long.class);
        assertTransactional("finalizeAssistantMessage",
                AuthPrincipal.class, Long.class, Long.class, String.class, List.class);
        assertTransactional("discardAssistantPlaceholder",
                AuthPrincipal.class, Long.class, Long.class);
    }

    private void assertTransactional(String name, Class<?>... parameterTypes) throws Exception {
        Method method = ChatService.class.getMethod(name, parameterTypes);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    private void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private ChatMessageDto message(
            Long id,
            String role,
            String content,
            List<SourceCitationDto> sources
    ) {
        return new ChatMessageDto(id, role, content, sources, time());
    }

    private SourceCitationDto citation() {
        return new SourceCitationDto(
                301L,
                SourceType.OFFICIAL,
                "Rules",
                "https://example.edu/rules",
                "Registrar",
                OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                0.87,
                "Relevant quote");
    }

    private OffsetDateTime time() {
        return OffsetDateTime.parse("2026-07-14T12:00:00Z");
    }
}

package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class UgcApprovedEventListenerTest {

    private UgcIndexDispatcher dispatcher;
    private UgcApprovedEventListener listener;

    @BeforeEach
    void setUp() {
        dispatcher = mock(UgcIndexDispatcher.class);
        listener = new UgcApprovedEventListener(dispatcher);
    }

    @Test
    void approvalHandlerRunsOnlyAfterTheReviewTransactionCommits() throws Exception {
        Method method = UgcApprovedEventListener.class
                .getDeclaredMethod("onApproved", UgcApprovedEvent.class);
        TransactionalEventListener annotation = method
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void committedApprovalOnlyDelegatesToTheAsyncDispatcher() {
        listener.onApproved(new UgcApprovedEvent(41L));

        verify(dispatcher).dispatch(41L);
    }

    @Test
    void unexpectedDispatchFailureDoesNotEscapeTheAfterCommitListener() {
        org.mockito.Mockito.doThrow(new IllegalStateException("executor failed"))
                .when(dispatcher).dispatch(41L);

        assertThatCode(() -> listener.onApproved(new UgcApprovedEvent(41L)))
                .doesNotThrowAnyException();

        verify(dispatcher).dispatch(41L);
    }
}

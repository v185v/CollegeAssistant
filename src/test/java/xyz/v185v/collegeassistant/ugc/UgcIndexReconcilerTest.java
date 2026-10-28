package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

class UgcIndexReconcilerTest {

    private UgcRepository ugcRepository;
    private UgcIndexDispatcher dispatcher;
    private UgcIndexReconciler reconciler;

    @BeforeEach
    void setUp() {
        ugcRepository = mock(UgcRepository.class);
        dispatcher = mock(UgcIndexDispatcher.class);
        reconciler = new UgcIndexReconciler(ugcRepository, dispatcher, 25);
    }

    @Test
    void scheduledScanUsesConfigurableDelays() throws Exception {
        Method method = UgcIndexReconciler.class.getMethod("reconcileMissingIndexes");
        Scheduled annotation = method.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.initialDelayString())
                .isEqualTo("${app.ugc-index.reconcile-initial-delay-ms:60000}");
        assertThat(annotation.fixedDelayString())
                .isEqualTo("${app.ugc-index.reconcile-fixed-delay-ms:300000}");
    }

    @Test
    void eachApprovedPostMissingChunksIsDispatchedFromAFiniteBatch() {
        when(ugcRepository.findApprovedWithoutChunksAfter(0L, 25))
                .thenReturn(List.of(41L, 42L));

        reconciler.reconcileMissingIndexes();

        verify(dispatcher).dispatch(41L);
        verify(dispatcher).dispatch(42L);
    }

    @Test
    void failedFirstBatchStillAdvancesSoLaterPostsGetADispatchOpportunity() {
        when(ugcRepository.findApprovedWithoutChunksAfter(0L, 25))
                .thenReturn(List.of(41L, 42L));
        when(ugcRepository.findApprovedWithoutChunksAfter(42L, 25))
                .thenReturn(List.of(43L));
        when(dispatcher.dispatch(41L)).thenReturn(false);
        when(dispatcher.dispatch(42L)).thenThrow(new IllegalStateException("executor failure"));

        assertThatCode(reconciler::reconcileMissingIndexes).doesNotThrowAnyException();
        assertThatCode(reconciler::reconcileMissingIndexes).doesNotThrowAnyException();

        verify(dispatcher).dispatch(41L);
        verify(dispatcher).dispatch(42L);
        verify(dispatcher).dispatch(43L);
        InOrder scans = inOrder(ugcRepository);
        scans.verify(ugcRepository).findApprovedWithoutChunksAfter(0L, 25);
        scans.verify(ugcRepository).findApprovedWithoutChunksAfter(42L, 25);
    }

    @Test
    void emptyTailBatchWrapsCursorSoOlderNewlyApprovedPostsAreEventuallyScanned() {
        when(ugcRepository.findApprovedWithoutChunksAfter(0L, 25))
                .thenReturn(List.of(41L), List.of(5L));
        when(ugcRepository.findApprovedWithoutChunksAfter(41L, 25))
                .thenReturn(List.of());

        reconciler.reconcileMissingIndexes();
        reconciler.reconcileMissingIndexes();
        reconciler.reconcileMissingIndexes();

        verify(ugcRepository, times(2)).findApprovedWithoutChunksAfter(0L, 25);
        verify(ugcRepository).findApprovedWithoutChunksAfter(41L, 25);
        verify(dispatcher).dispatch(5L);
    }

    @Test
    void scanFailureDoesNotAdvanceCursor() {
        when(ugcRepository.findApprovedWithoutChunksAfter(0L, 25))
                .thenThrow(new IllegalStateException("database message with sensitive detail"))
                .thenReturn(List.of(41L));

        assertThatCode(reconciler::reconcileMissingIndexes).doesNotThrowAnyException();
        reconciler.reconcileMissingIndexes();

        verify(ugcRepository, times(2)).findApprovedWithoutChunksAfter(0L, 25);
        verify(dispatcher).dispatch(41L);
    }

    @Test
    void constructorRejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new UgcIndexReconciler(ugcRepository, dispatcher, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size");
    }
}

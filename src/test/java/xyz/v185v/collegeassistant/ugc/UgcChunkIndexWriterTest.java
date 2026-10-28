package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;

class UgcChunkIndexWriterTest {

    private UgcRepository ugcRepository;
    private EmbeddingChunkRepository chunkRepository;
    private EmbeddingMaintenanceGuard maintenanceGuard;
    private UgcChunkIndexWriter writer;

    @BeforeEach
    void setUp() {
        ugcRepository = mock(UgcRepository.class);
        chunkRepository = mock(EmbeddingChunkRepository.class);
        maintenanceGuard = mock(EmbeddingMaintenanceGuard.class);
        writer = new UgcChunkIndexWriter(ugcRepository, chunkRepository, maintenanceGuard);
    }

    @Test
    void statusLockAndReplacementShareARequiresNewShortTransaction() throws Exception {
        Method method = UgcChunkIndexWriter.class
                .getMethod("replaceIfStillApproved", Long.class, long.class, List.class);
        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void noLongerApprovedPostIsLockedButNeverReplaced() {
        when(ugcRepository.lockIfApproved(41L)).thenReturn(false);

        boolean replaced = writer.replaceIfStillApproved(41L, 1L, List.of());

        assertThat(replaced).isFalse();
        verify(chunkRepository, never()).replace(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void stillApprovedPostIsReplacedOnlyAfterItsStatusRowIsLocked() {
        List<EmbeddingChunkRecord> records = List.of();
        when(ugcRepository.lockIfApproved(41L)).thenReturn(true);

        boolean replaced = writer.replaceIfStillApproved(41L, 1L, records);

        assertThat(replaced).isTrue();
        InOrder order = inOrder(ugcRepository, chunkRepository);
        order.verify(ugcRepository).lockIfApproved(41L);
        order.verify(chunkRepository).replace("UGC", 41L, 1L, records);
    }

    @Test
    void maintenanceGuardRunsBeforeTheUgcStatusLock() {
        when(ugcRepository.lockIfApproved(41L)).thenReturn(false);

        writer.replaceIfStillApproved(41L, 1L, List.of());

        InOrder order = inOrder(maintenanceGuard, ugcRepository);
        order.verify(maintenanceGuard).requireWritesAllowed();
        order.verify(ugcRepository).lockIfApproved(41L);
    }

    @Test
    void replacementFailurePropagatesSoTheShortTransactionCanRollBack() {
        List<EmbeddingChunkRecord> records = List.of();
        when(ugcRepository.lockIfApproved(41L)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("database failed");
        when(chunkRepository.replace("UGC", 41L, 1L, records)).thenThrow(failure);

        assertThatThrownBy(() -> writer.replaceIfStillApproved(41L, 1L, records))
                .isSameAs(failure);
    }
}

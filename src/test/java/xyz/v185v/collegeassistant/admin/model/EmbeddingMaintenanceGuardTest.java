package xyz.v185v.collegeassistant.admin.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class EmbeddingMaintenanceGuardTest {

    @Test
    void permitsOrdinaryWritesWhenNoPendingEmbeddingRebuildExists() {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        when(jobs.hasBlockingEmbeddingRebuildForPendingConfiguration()).thenReturn(false);

        EmbeddingMaintenanceGuard guard = new EmbeddingMaintenanceGuard(jobs);

        assertThatCode(guard::requireWritesAllowed).doesNotThrowAnyException();
        verify(jobs).lockEmbeddingMaintenanceState();
    }

    @Test
    void blocksOrdinaryWritesForEveryPendingRebuildTerminalState() {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        when(jobs.hasBlockingEmbeddingRebuildForPendingConfiguration()).thenReturn(true);

        EmbeddingMaintenanceGuard guard = new EmbeddingMaintenanceGuard(jobs);

        assertThatThrownBy(guard::requireWritesAllowed)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.EMBEDDING_REBUILD_IN_PROGRESS);
                    assertThat(exception.getMessage()).contains("maintenance");
                });
    }
}

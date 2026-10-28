package xyz.v185v.collegeassistant.admin.model;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.job.LeaseGuardedJobOperations;
import xyz.v185v.collegeassistant.ai.config.EmbeddingPartitionRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;

class IndexCleanupWorkerTest {

    @Test
    @SuppressWarnings("unchecked")
    void dropsOnlyARetiredCandidatePartitionInsideTheJobLeaseTransaction() {
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        EmbeddingPartitionRepository partitions = mock(EmbeddingPartitionRepository.class);
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        AdminJobItemDto item = mock(AdminJobItemDto.class);
        when(item.status()).thenReturn(AdminJobStatus.QUEUED);
        when(context.job()).thenReturn(job());
        when(context.upsertItem(any(), any(), any(), any())).thenReturn(item);
        when(configurations.lockById(31L)).thenReturn(Optional.of(retired(31L)));
        when(context.inLeaseTransaction(any())).thenAnswer(invocation ->
                ((java.util.function.Function<LeaseGuardedJobOperations, Object>)
                        invocation.getArgument(0)).apply(operations));
        IndexCleanupWorker worker = new IndexCleanupWorker(configurations, partitions);

        worker.execute(context);

        verify(partitions).drop(31L);
        verify(operations).checkpoint(item.id(), AdminJobStatus.SUCCEEDED,
                Map.of("stage", "PARTITION_DROPPED", "embeddingConfigurationId", 31L));
    }

    private AdminJobDto job() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new AdminJobDto(
                71L, AdminJobType.INDEX_CLEANUP, AdminJobStatus.RUNNING,
                EmbeddingRebuildService.CONFIGURATION_TARGET_TYPE, 31L,
                3L, 0, null, null, null, null, null, null, null, null,
                Map.of(), now, now, java.util.List.of());
    }

    private ModelConfiguration retired(long id) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new ModelConfiguration(
                id, "embedding-" + id, ModelKind.EMBEDDING,
                "openai", "text-embedding-3-small", "https://api.openai.com/v1",
                null, 1536, null, null, null,
                ModelConfigurationStatus.RETIRED, 3L, null, "Discarded", now, now);
    }
}

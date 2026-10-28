package xyz.v185v.collegeassistant.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.ai.config.EmbeddingPartitionRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class EmbeddingMaintenanceGuardIT extends DatabaseTestSupport {

    @Autowired private EmbeddingMaintenanceGuard guard;
    @Autowired private AdminJobRepository jobs;
    @Autowired private ModelConfigurationRepository configurations;
    @Autowired private EmbeddingPartitionRepository partitions;
    @Autowired private JdbcClient jdbcClient;

    private Long candidateId;

    @AfterEach
    void removeCandidatePartition() {
        if (candidateId == null) {
            return;
        }
        jdbcClient.sql("DELETE FROM admin_job WHERE target_id = :id")
                .param("id", candidateId)
                .update();
        partitions.drop(candidateId);
        jdbcClient.sql("DELETE FROM model_configuration WHERE id = :id")
                .param("id", candidateId)
                .update();
    }

    @ParameterizedTest
    @EnumSource(value = AdminJobStatus.class, names = {
            "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "PARTIAL_SUCCESS"
    })
    void pendingCandidateBlocksWritesForEveryFencedRebuildState(AdminJobStatus status) {
        long configurationId = createPendingCandidate();
        AdminJobDto job = jobs.create(
                AdminJobType.EMBEDDING_REBUILD,
                EmbeddingRebuildService.CONFIGURATION_TARGET_TYPE,
                configurationId,
                null,
                Map.of("embeddingConfigurationId", configurationId));
        updateStatus(job.id(), status);

        assertThatThrownBy(guard::requireWritesAllowed)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode())
                            .isEqualTo(ErrorCode.EMBEDDING_REBUILD_IN_PROGRESS);
                    assertThat(exception.getMessage()).contains("maintenance");
                });
    }

    @Test
    void cancelledQueuedRebuildReleasesTheFenceForItsDiscardTransaction() {
        long configurationId = createPendingCandidate();
        jobs.create(
                AdminJobType.EMBEDDING_REBUILD,
                EmbeddingRebuildService.CONFIGURATION_TARGET_TYPE,
                configurationId,
                null,
                Map.of("embeddingConfigurationId", configurationId));

        assertThat(jobs.cancelQueuedEmbeddingRebuild(configurationId)).isTrue();

        assertThatCode(guard::requireWritesAllowed).doesNotThrowAnyException();
    }

    private long createPendingCandidate() {
        candidateId = configurations.reserveId();
        OffsetDateTime now = OffsetDateTime.now();
        configurations.publishPending(new ModelConfiguration(
                candidateId, "embedding-" + candidateId, ModelKind.EMBEDDING,
                "ollama", "qwen3-embedding:4b", "http://localhost:11434",
                null, 1536, null, null, null,
                ModelConfigurationStatus.PENDING, null, null, null, now, now));
        return candidateId;
    }

    private void updateStatus(long jobId, AdminJobStatus status) {
        if (status == AdminJobStatus.QUEUED) {
            return;
        }
        if (status == AdminJobStatus.RUNNING) {
            jdbcClient.sql("""
                            UPDATE admin_job
                            SET status = 'RUNNING', worker_id = 'maintenance-it',
                                lease_token = CAST(:leaseToken AS uuid),
                                claimed_at = now(), heartbeat_at = now(), started_at = now()
                            WHERE id = :jobId
                            """)
                    .param("leaseToken", UUID.randomUUID().toString())
                    .param("jobId", jobId)
                    .update();
            return;
        }
        jdbcClient.sql("UPDATE admin_job SET status = :status WHERE id = :jobId")
                .param("status", status.name())
                .param("jobId", jobId)
                .update();
    }
}

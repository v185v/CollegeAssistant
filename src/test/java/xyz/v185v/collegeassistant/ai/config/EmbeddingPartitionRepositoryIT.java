package xyz.v185v.collegeassistant.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class EmbeddingPartitionRepositoryIT extends DatabaseTestSupport {

    @Autowired private EmbeddingPartitionRepository partitions;
    @Autowired private ModelConfigurationRepository configurations;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void dropCreatedPartitions() {
        for (Long id : createdIds.reversed()) {
            jdbcClient.sql("""
                            DELETE FROM admin_job
                            WHERE target_type = 'EMBEDDING_CONFIGURATION' AND target_id = :id
                            """)
                    .param("id", id)
                    .update();
            jdbcClient.sql("DROP TABLE IF EXISTS embedding_chunk_cfg_" + id).update();
            jdbcClient.sql("DELETE FROM model_configuration WHERE id = :id")
                    .param("id", id).update();
        }
    }

    @Test
    void createsListPartitionAndInheritedHnswIndexForServerSequenceId() {
        long id = new TransactionTemplate(transactionManager).execute(status -> {
            long reserved = configurations.reserveId();
            createdIds.add(reserved);
            partitions.create(reserved);
            configurations.insert(configuration(reserved));
            return reserved;
        });

        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM pg_inherits inheritance
                        JOIN pg_class child ON child.oid = inheritance.inhrelid
                        JOIN pg_class parent ON parent.oid = inheritance.inhparent
                        WHERE parent.relname = 'embedding_chunk'
                          AND child.relname = :partition
                        """)
                .param("partition", "embedding_chunk_cfg_" + id)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND tablename = :partition
                          AND indexdef ILIKE '% USING hnsw %'
                        """)
                .param("partition", "embedding_chunk_cfg_" + id)
                .query(Long.class).single()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void partitionDdlRollsBackWithFailedConfigurationTransaction() {
        long id = configurations.reserveId();
        createdIds.add(id);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            partitions.create(id);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcClient.sql("SELECT to_regclass(:name) IS NULL")
                .param("name", "embedding_chunk_cfg_" + id)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void publishingEmbeddingConfigurationRollsBackItsPartitionAndPendingRetirement() {
        long previousId = configurations.reserveId();
        createdIds.add(previousId);
        configurations.insert(configuration(previousId));
        long candidateId = configurations.reserveId();
        createdIds.add(candidateId);
        OffsetDateTime now = OffsetDateTime.now();
        ModelConfiguration duplicateKey = new ModelConfiguration(
                candidateId, "bootstrap-embedding", ModelKind.EMBEDDING,
                "ollama", "qwen3-embedding:4b", "http://localhost:11434",
                null, 1536, null, null, null,
                ModelConfigurationStatus.PENDING, null, null, null, now, now);

        assertThatThrownBy(() -> configurations.publishPending(duplicateKey))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcClient.sql("""
                        SELECT status
                        FROM model_configuration
                        WHERE id = :id
                        """)
                .param("id", previousId)
                .query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbcClient.sql("SELECT to_regclass(:name) IS NULL")
                .param("name", "embedding_chunk_cfg_" + candidateId)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void refusesToReplaceAnEmbeddingCandidateUntilItIsExplicitlyDiscarded() {
        long existingId = configurations.reserveId();
        long replacementId = configurations.reserveId();
        createdIds.add(existingId);
        createdIds.add(replacementId);
        configurations.publishPending(configuration(existingId));

        assertThatThrownBy(() -> configurations.publishPending(configuration(replacementId)))
                .isInstanceOf(RuntimeException.class);

        assertThat(configurations.findById(existingId))
                .hasValueSatisfying(configuration ->
                        assertThat(configuration.status()).isEqualTo(
                                ModelConfigurationStatus.PENDING));
        assertThat(configurations.findById(replacementId)).isEmpty();
        assertThat(jdbcClient.sql("SELECT to_regclass(:name) IS NULL")
                .param("name", "embedding_chunk_cfg_" + replacementId)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void activatesPendingEmbeddingCandidateAndRetiresCurrentEmbeddingVersion() {
        long activeId = configurations.findActive(ModelKind.EMBEDDING)
                .orElseThrow().id();
        long candidateId = configurations.reserveId();
        createdIds.add(candidateId);
        configurations.publishPending(configuration(candidateId));

        ModelConfiguration activated = configurations.activateEmbedding(candidateId);

        assertThat(activated.status()).isEqualTo(ModelConfigurationStatus.ACTIVE);
        assertThat(configurations.findActive(ModelKind.EMBEDDING))
                .hasValueSatisfying(configuration -> {
                    assertThat(configuration.id()).isEqualTo(candidateId);
                    assertThat(configuration.status()).isEqualTo(
                            ModelConfigurationStatus.ACTIVE);
                });
        assertThat(configurations.findById(activeId))
                .hasValueSatisfying(configuration ->
                        assertThat(configuration.status()).isEqualTo(
                                ModelConfigurationStatus.RETIRED));
    }

    @Test
    void retiresPendingEmbeddingCandidateByIdWithDiscardReason() {
        long candidateId = configurations.reserveId();
        createdIds.add(candidateId);
        configurations.publishPending(configuration(candidateId));

        ModelConfiguration retired = configurations.retirePendingEmbedding(
                candidateId, "Embedding rebuild discarded");

        assertThat(retired.status()).isEqualTo(ModelConfigurationStatus.RETIRED);
        assertThat(retired.failureReason()).isEqualTo("Embedding rebuild discarded");
        assertThat(configurations.findPending(ModelKind.EMBEDDING)).isEmpty();
    }

    @Test
    void failingEmbeddingCandidateQueuesItsPartitionForCleanup() {
        long candidateId = configurations.reserveId();
        createdIds.add(candidateId);
        configurations.publishPending(configuration(candidateId));

        assertThat(configurations.markEmbeddingFailedAndScheduleCleanup(
                candidateId, "Embedding model initialization failed")).isTrue();

        assertThat(configurations.findById(candidateId))
                .hasValueSatisfying(configuration ->
                        assertThat(configuration.status()).isEqualTo(
                                ModelConfigurationStatus.FAILED));
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM admin_job
                        WHERE job_type = 'INDEX_CLEANUP'
                          AND target_type = 'EMBEDDING_CONFIGURATION'
                          AND target_id = :id
                          AND status = 'QUEUED'
                        """)
                .param("id", candidateId)
                .query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void dropsOnlyTheDeterministicCandidatePartitionAndRejectsInvalidIds() {
        long id = configurations.reserveId();
        createdIds.add(id);
        partitions.create(id);

        partitions.drop(id);

        assertThat(jdbcClient.sql("SELECT to_regclass(:name) IS NULL")
                .param("name", partitions.partitionName(id))
                .query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> partitions.drop(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Embedding configuration id must be positive");
    }

    private ModelConfiguration configuration(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                id, "embedding-" + id, ModelKind.EMBEDDING,
                "ollama", "qwen3-embedding:4b", "http://localhost:11434",
                null, 1536, null, null, null,
                ModelConfigurationStatus.PENDING, null, null, null, now, now);
    }
}

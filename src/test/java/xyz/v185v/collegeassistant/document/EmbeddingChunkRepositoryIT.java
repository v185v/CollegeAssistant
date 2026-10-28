package xyz.v185v.collegeassistant.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.ai.config.EmbeddingPartitionRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class EmbeddingChunkRepositoryIT extends DatabaseTestSupport {

    private static final long DOCUMENT_ID = 90_101L;

    @Autowired private EmbeddingChunkRepository chunks;
    @Autowired private ModelConfigurationRepository configurations;
    @Autowired private EmbeddingPartitionRepository partitions;
    @Autowired private JdbcClient jdbcClient;

    private Long candidateId;

    @AfterEach
    void removeCandidatePartitionAndTestChunks() {
        jdbcClient.sql("DELETE FROM embedding_chunk WHERE doc_ref_id = :documentId")
                .param("documentId", DOCUMENT_ID)
                .update();
        if (candidateId != null) {
            jdbcClient.sql("DROP TABLE IF EXISTS " + partitions.partitionName(candidateId)).update();
            jdbcClient.sql("DELETE FROM model_configuration WHERE id = :id")
                    .param("id", candidateId)
                    .update();
        }
    }

    @Test
    void candidateReplacementCannotDeleteChunksFromTheActiveVectorSpace() {
        long activeId = jdbcClient.sql("""
                        SELECT id
                        FROM model_configuration
                        WHERE configuration_key = 'bootstrap-embedding'
                        """)
                .query(Long.class)
                .single();
        candidateId = configurations.reserveId();
        configurations.publishPending(candidate(candidateId));

        chunks.replace("DOCUMENT", DOCUMENT_ID, activeId, List.of(
                chunk(activeId, "active chunk")));
        chunks.replace("DOCUMENT", DOCUMENT_ID, candidateId, List.of(
                chunk(candidateId, "candidate chunk")));
        chunks.replace("DOCUMENT", DOCUMENT_ID, candidateId, List.of(
                chunk(candidateId, "candidate replacement")));

        assertThat(texts(activeId)).containsExactly("active chunk");
        assertThat(texts(candidateId)).containsExactly("candidate replacement");
        assertThat(chunks.deleteAllForReference("DOCUMENT", DOCUMENT_ID)).isEqualTo(2);
        assertThat(texts(activeId)).isEmpty();
        assertThat(texts(candidateId)).isEmpty();
        assertThat(chunks.deleteAllForReference("DOCUMENT", DOCUMENT_ID)).isZero();
    }

    @Test
    void listsOnlyDistinctTargetsWithinTheRequestedCandidateSpace() {
        candidateId = configurations.reserveId();
        configurations.publishPending(candidate(candidateId));
        chunks.replace("DOCUMENT", DOCUMENT_ID, candidateId, List.of(
                chunk(candidateId, "candidate document"),
                new EmbeddingChunkRecord(null, candidateId, "DOCUMENT", DOCUMENT_ID, 1,
                        "second chunk", unitVector(), "OFFICIAL", null, "Document", null,
                        null, "GENERAL", "GENERAL", "GENERAL", "GENERAL", Map.of(), null)));
        chunks.replace("UGC", DOCUMENT_ID + 1, candidateId, List.of(
                ugcChunk(candidateId, "candidate post")));

        assertThat(chunks.findTargetsByConfiguration(candidateId)).containsExactly(
                new EmbeddingChunkTarget("DOCUMENT", DOCUMENT_ID),
                new EmbeddingChunkTarget("UGC", DOCUMENT_ID + 1));
    }

    private List<String> texts(long configurationId) {
        return jdbcClient.sql("""
                        SELECT chunk_text
                        FROM embedding_chunk
                        WHERE embedding_config_id = :configurationId
                          AND doc_ref_id = :documentId
                        ORDER BY chunk_index
                        """)
                .param("configurationId", configurationId)
                .param("documentId", DOCUMENT_ID)
                .query(String.class)
                .list();
    }

    private ModelConfiguration candidate(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                id, "embedding-" + id, ModelKind.EMBEDDING,
                "ollama", "qwen3-embedding:4b", "http://localhost:11434",
                null, 1536, null, null, null,
                ModelConfigurationStatus.PENDING, null, null, null, now, now);
    }

    private EmbeddingChunkRecord chunk(long configurationId, String text) {
        List<Double> embedding = unitVector();
        return new EmbeddingChunkRecord(
                null, configurationId, "DOCUMENT", DOCUMENT_ID, 0, text, embedding,
                "OFFICIAL", "https://example.edu/document", "Document", "Registrar",
                null, "GENERAL", "GENERAL", "GENERAL", "GENERAL", Map.of(), null);
    }

    private EmbeddingChunkRecord ugcChunk(long configurationId, String text) {
        List<Double> embedding = unitVector();
        return new EmbeddingChunkRecord(
                null, configurationId, "UGC", DOCUMENT_ID + 1, 0, text, embedding,
                "UGC", null, "Post", "Student", null,
                "GENERAL", "GENERAL", "GENERAL", "GENERAL", Map.of(), null);
    }

    private List<Double> unitVector() {
        List<Double> embedding = new java.util.ArrayList<>(
                java.util.Collections.nCopies(1536, 0.0));
        embedding.set(0, 1.0);
        return embedding;
    }
}

package xyz.v185v.collegeassistant.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.v185v.collegeassistant.ai.config.EmbeddingPartitionRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class EmbeddingPartitionSearchIT extends DatabaseTestSupport {

    private static final int DIMENSIONS = 1536;

    @Autowired private EmbeddingChunkRepository chunks;
    @Autowired private DocumentRepository documents;
    @Autowired private SourceRepository sources;
    @Autowired private ModelConfigurationRepository configurations;
    @Autowired private EmbeddingPartitionRepository partitions;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long candidateId;

    @AfterEach
    void removeCandidatePartition() {
        if (candidateId == null) {
            return;
        }
        partitions.drop(candidateId);
        jdbcClient.sql("DELETE FROM model_configuration WHERE id = :id")
                .param("id", candidateId)
                .update();
    }

    @Test
    void searchUsesOnlyTheActivePartitionAndItsHnswIndex() {
        long activeId = configurations.findActive(ModelKind.EMBEDDING).orElseThrow().id();
        candidateId = configurations.reserveId();
        configurations.publishPending(candidate(candidateId));

        DocumentRecord activeDocument = createDocument("active-vector-space");
        DocumentRecord candidateDocument = createDocument("candidate-vector-space");
        List<Double> query = unitVector(0);
        chunks.replace("DOCUMENT", activeDocument.id(), activeId, List.of(
                chunk(activeId, activeDocument.id(), "active-only", unitVector(1))));
        chunks.replace("DOCUMENT", candidateDocument.id(), candidateId, List.of(
                chunk(candidateId, candidateDocument.id(), "candidate-would-dominate", query)));

        List<EmbeddingChunkSearchResult> results = chunks.search(
                new EmbeddingChunkSearchCriteria(
                        activeId, query, 3, "OFFICIAL", null, null, null, null));

        assertThat(results).extracting(result -> result.chunk().chunkText())
                .containsExactly("active-only");
        assertThat(results).extracting(result -> result.chunk().embeddingConfigId())
                .containsOnly(activeId);

        String activePartition = partitions.partitionName(activeId);
        String activeIndex = jdbcClient.sql("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND tablename = :partition
                          AND indexdef ILIKE '% USING hnsw %'
                        """)
                .param("partition", activePartition)
                .query(String.class)
                .single();
        String plan = Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
            jdbcClient.sql("SET LOCAL enable_sort = off").update();
            return jdbcClient.sql("""
                            EXPLAIN (ANALYZE, FORMAT JSON)
                            SELECT id
                            FROM embedding_chunk
                            WHERE embedding_config_id = :embeddingConfigId
                            ORDER BY embedding <=> CAST(:queryEmbedding AS vector)
                            LIMIT 3
                            """)
                    .param("embeddingConfigId", activeId)
                    .param("queryEmbedding", vectorLiteral(query))
                    .query(String.class)
                    .single();
        }));

        assertThat(plan)
                .contains(activePartition, activeIndex)
                .doesNotContain(partitions.partitionName(candidateId));
    }

    private DocumentRecord createDocument(String slug) {
        SourceRecord source = sources.create(new SourceRecord(
                null, "Source " + slug, "https://" + slug + ".example.edu",
                "OFFICIAL", true, null, null));
        return documents.write(new DocumentRecord(
                null, source.id(), "Document " + slug, "https://example.edu/" + slug,
                "Example University", OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                "hash-" + slug, "documents/" + slug + ".txt", null), false).document();
    }

    private EmbeddingChunkRecord chunk(
            long configurationId,
            long documentId,
            String text,
            List<Double> embedding
    ) {
        return new EmbeddingChunkRecord(
                null, configurationId, "DOCUMENT", documentId, 0, text, embedding,
                "OFFICIAL", "https://example.edu/" + documentId, "Document " + documentId,
                "Example University", OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                "GENERAL", "GENERAL", "GENERAL", "GENERAL", Map.of(), null);
    }

    private ModelConfiguration candidate(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                id, "embedding-" + id, ModelKind.EMBEDDING,
                "ollama", "qwen3-embedding:4b", "http://localhost:11434",
                null, DIMENSIONS, null, null, null,
                ModelConfigurationStatus.PENDING, null, null, null, now, now);
    }

    private List<Double> unitVector(int dimension) {
        List<Double> vector = new ArrayList<>(java.util.Collections.nCopies(DIMENSIONS, 0.0));
        vector.set(dimension, 1.0);
        return vector;
    }

    private String vectorLiteral(List<Double> vector) {
        return vector.stream().map(value -> Double.toString(value))
                .collect(Collectors.joining(",", "[", "]"));
    }
}

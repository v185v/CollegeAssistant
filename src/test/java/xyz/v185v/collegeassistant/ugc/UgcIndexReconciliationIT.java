package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class UgcIndexReconciliationIT extends DatabaseTestSupport {

    private static final long EMBEDDING_CONFIG_ID = 2L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private UgcRepository ugcRepository;

    @Autowired
    private EmbeddingChunkRepository chunkRepository;

    @Test
    void scanReturnsOnlyApprovedPostsWithoutAnyUgcChunksAndHonorsLimit() {
        Long authorId = insertUser();
        Long firstMissing = insertPost(authorId, "first missing", "APPROVED");
        Long secondMissing = insertPost(authorId, "second missing", "APPROVED");
        Long alreadyIndexed = insertPost(authorId, "already indexed", "APPROVED");
        Long malformedIndex = insertPost(authorId, "malformed index", "APPROVED");
        insertPost(authorId, "pending", "PENDING");
        insertPost(authorId, "rejected", "REJECTED");
        chunkRepository.replace("UGC", alreadyIndexed, EMBEDDING_CONFIG_ID, List.of(chunk(alreadyIndexed)));
        chunkRepository.replace(
                "UGC", malformedIndex, EMBEDDING_CONFIG_ID, List.of(chunk(malformedIndex, "OFFICIAL")));

        assertThat(ugcRepository.findApprovedWithoutChunksAfter(0L, 10))
                .containsExactly(firstMissing, secondMissing, malformedIndex);
        assertThat(ugcRepository.findApprovedWithoutChunksAfter(0L, 1))
                .containsExactly(firstMissing);
        assertThat(ugcRepository.findApprovedWithoutChunksAfter(firstMissing, 10))
                .containsExactly(secondMissing, malformedIndex);
    }

    private Long insertUser() {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES ('ugc-reconcile-author', 'hash', 'Reconcile Author', 'STUDENT')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
    }

    private Long insertPost(Long authorId, String title, String status) {
        return jdbcClient.sql("""
                        INSERT INTO ugc_post (author_id, title, content, status)
                        VALUES (:authorId, :title, :content, :status)
                        RETURNING id
                        """)
                .param("authorId", authorId)
                .param("title", title)
                .param("content", "Detailed reconciliation content for " + title)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private EmbeddingChunkRecord chunk(Long postId) {
        return chunk(postId, "UGC");
    }

    private EmbeddingChunkRecord chunk(Long postId, String sourceType) {
        List<Double> vector = new ArrayList<>(
                Collections.nCopies(EmbeddingDimensions.DIMENSIONS, 0.0));
        vector.set(0, 1.0);
        return new EmbeddingChunkRecord(
                null,
                EMBEDDING_CONFIG_ID,
                "UGC",
                postId,
                0,
                "already indexed",
                vector,
                sourceType,
                null,
                "Already indexed",
                "Reconcile Author",
                OffsetDateTime.parse("2026-07-14T12:00:00Z"),
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                Map.of("authorId", 1L),
                null);
    }
}

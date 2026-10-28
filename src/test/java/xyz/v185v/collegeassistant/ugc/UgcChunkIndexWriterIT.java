package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class UgcChunkIndexWriterIT extends DatabaseTestSupport {

    private static final long EMBEDDING_CONFIG_ID = 2L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private UgcChunkIndexWriter writer;

    @Test
    void approvedPostReplacesChunksButRemovedPostDoesNotWriteANewReplacement() {
        Long postId = insertPost("APPROVED");

        assertThat(writer.replaceIfStillApproved(
                postId, EMBEDDING_CONFIG_ID, List.of(chunk(postId, 0, "approved", "UGC"))))
                .isTrue();
        assertThat(chunkTexts(postId)).containsExactly("approved");

        setStatus(postId, "REMOVED");
        assertThat(writer.replaceIfStillApproved(
                postId, EMBEDDING_CONFIG_ID, List.of(chunk(postId, 0, "removed", "UGC"))))
                .isFalse();
        assertThat(chunkTexts(postId)).containsExactly("approved");
    }

    @Test
    void failedReplacementRollsBackAndRetainsThePreviousCompleteChunks() {
        Long postId = insertPost("APPROVED");
        writer.replaceIfStillApproved(
                postId, EMBEDDING_CONFIG_ID, List.of(chunk(postId, 0, "old retained", "UGC")));

        List<EmbeddingChunkRecord> invalidBatch = List.of(
                chunk(postId, 0, "new first", "UGC"),
                chunk(postId, 1, "invalid second", "X".repeat(33)));

        assertThatThrownBy(() -> writer.replaceIfStillApproved(
                postId, EMBEDDING_CONFIG_ID, invalidBatch))
                .isInstanceOf(DataAccessException.class);
        assertThat(chunkTexts(postId)).containsExactly("old retained");
    }

    private Long insertPost(String status) {
        Long authorId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES ('writer-author', 'hash', 'Writer Author', 'STUDENT')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        return jdbcClient.sql("""
                        INSERT INTO ugc_post (author_id, title, content, status)
                        VALUES (:authorId, 'Writer test', 'Detailed writer test content', :status)
                        RETURNING id
                        """)
                .param("authorId", authorId)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private void setStatus(Long postId, String status) {
        jdbcClient.sql("UPDATE ugc_post SET status = :status WHERE id = :postId")
                .param("status", status)
                .param("postId", postId)
                .update();
    }

    private List<String> chunkTexts(Long postId) {
        return jdbcClient.sql("""
                        SELECT chunk_text
                        FROM embedding_chunk
                        WHERE embedding_config_id = :embeddingConfigId
                          AND doc_ref_type = 'UGC' AND doc_ref_id = :postId
                        ORDER BY chunk_index
                        """)
                .param("postId", postId)
                .param("embeddingConfigId", EMBEDDING_CONFIG_ID)
                .query(String.class)
                .list();
    }

    private EmbeddingChunkRecord chunk(
            Long postId,
            int index,
            String text,
            String sourceType
    ) {
        List<Double> vector = new ArrayList<>(
                Collections.nCopies(EmbeddingDimensions.DIMENSIONS, 0.0));
        vector.set(index, 1.0);
        return new EmbeddingChunkRecord(
                null,
                EMBEDDING_CONFIG_ID,
                "UGC",
                postId,
                index,
                text,
                vector,
                sourceType,
                null,
                "Writer test",
                "Writer Author",
                OffsetDateTime.parse("2026-07-14T12:00:00Z"),
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                Map.of("authorId", 1L),
                null);
    }
}

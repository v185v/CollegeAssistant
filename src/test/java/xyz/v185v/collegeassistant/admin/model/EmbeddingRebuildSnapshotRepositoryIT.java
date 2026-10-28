package xyz.v185v.collegeassistant.admin.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.document.DocumentEmbeddingRebuildCandidate;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;
import xyz.v185v.collegeassistant.ugc.CreateUgcPostRequest;
import xyz.v185v.collegeassistant.ugc.UgcEmbeddingRebuildCandidate;
import xyz.v185v.collegeassistant.ugc.UgcPostDto;
import xyz.v185v.collegeassistant.ugc.UgcRepository;

class EmbeddingRebuildSnapshotRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UgcRepository ugcRepository;

    @Test
    void documentSnapshotIncludesOnlyLiveActiveDocumentsAndLocksThemForRebuild() {
        SourceRecord liveSource = sourceRepository.create(source("Live source"));
        DocumentRecord liveDocument = documentRepository.write(
                document(liveSource.id(), "live-document", "live-content-v1"), false).document();

        SourceRecord deletingSource = sourceRepository.create(source("Deleting source"));
        DocumentRecord deletingSourceDocument = documentRepository.write(
                document(deletingSource.id(), "deleting-source-document", "deleting-source-v1"), false).document();
        jdbcClient.sql("UPDATE source SET deletion_status = 'DELETING' WHERE id = :sourceId")
                .param("sourceId", deletingSource.id())
                .update();

        DocumentRecord deletingDocument = documentRepository.write(
                document(liveSource.id(), "deleting-document", "deleting-v1"), false).document();
        jdbcClient.sql("UPDATE document SET document_status = 'DELETING' WHERE id = :documentId")
                .param("documentId", deletingDocument.id())
                .update();

        assertThat(documentRepository.findActiveForEmbeddingRebuild())
                .containsExactly(new DocumentEmbeddingRebuildCandidate(
                        liveDocument.id(), liveDocument.contentHash()));
        assertThat(documentRepository.lockActiveForEmbeddingRebuild(liveDocument.id()))
                .contains(liveDocument);
        assertThat(documentRepository.lockActiveForEmbeddingRebuild(deletingSourceDocument.id()))
                .isEmpty();
        assertThat(documentRepository.lockActiveForEmbeddingRebuild(deletingDocument.id()))
                .isEmpty();
    }

    @Test
    void ugcSnapshotUsesAContentVersionAndLocksOnlyStillApprovedPosts() {
        Long authorId = insertUser("rebuild-snapshot-author", "Snapshot Author");
        UgcPostDto approved = ugcRepository.create(authorId, request("Approved post", "First body"));
        UgcPostDto pending = ugcRepository.create(authorId, request("Pending post", "Pending body"));
        approve(approved.id());

        UgcEmbeddingRebuildCandidate snapshot = ugcRepository.findApprovedForEmbeddingRebuild()
                .getFirst();

        assertThat(snapshot.post()).extracting(UgcPostDto::id, UgcPostDto::title)
                .containsExactly(approved.id(), approved.title());
        assertThat(snapshot.contentVersion()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ugcRepository.lockApprovedForEmbeddingRebuild(approved.id()))
                .contains(snapshot);
        assertThat(ugcRepository.lockApprovedForEmbeddingRebuild(pending.id())).isEmpty();

        jdbcClient.sql("""
                        UPDATE ugc_post
                        SET content = :content,
                            updated_at = now()
                        WHERE id = :postId
                        """)
                .param("content", "Changed body")
                .param("postId", approved.id())
                .update();

        UgcEmbeddingRebuildCandidate changed = ugcRepository.lockApprovedForEmbeddingRebuild(approved.id())
                .orElseThrow();
        assertThat(changed.contentVersion()).isNotEqualTo(snapshot.contentVersion());

        jdbcClient.sql("UPDATE ugc_post SET status = 'REMOVED' WHERE id = :postId")
                .param("postId", approved.id())
                .update();
        assertThat(ugcRepository.lockApprovedForEmbeddingRebuild(approved.id())).isEmpty();
    }

    private SourceRecord source(String name) {
        return new SourceRecord(null, name, "https://example.edu/" + name.replace(' ', '-'),
                "OFFICIAL", true, null, null);
    }

    private DocumentRecord document(Long sourceId, String slug, String contentHash) {
        return new DocumentRecord(
                null,
                sourceId,
                "Document " + slug,
                "https://example.edu/" + slug,
                "Example University",
                null,
                contentHash,
                "documents/" + slug + ".html",
                null);
    }

    private CreateUgcPostRequest request(String title, String content) {
        return new CreateUgcPostRequest(title, content, List.of("rebuild"), "CS101", "West Campus");
    }

    private Long insertUser(String username, String displayName) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, 'test-password-hash', :displayName, 'STUDENT')
                        RETURNING id
                        """)
                .param("username", username)
                .param("displayName", displayName)
                .query(Long.class)
                .single();
    }

    private void approve(Long postId) {
        jdbcClient.sql("""
                        UPDATE ugc_post
                        SET status = 'APPROVED',
                            reviewed_at = now(),
                            updated_at = now()
                        WHERE id = :postId
                        """)
                .param("postId", postId)
                .update();
    }
}

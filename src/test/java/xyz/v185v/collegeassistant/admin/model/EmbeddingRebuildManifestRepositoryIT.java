package xyz.v185v.collegeassistant.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class EmbeddingRebuildManifestRepositoryIT extends DatabaseTestSupport {

    private static final String CHECKSUM_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private EmbeddingRebuildManifestRepository repository;

    @Autowired
    private AdminJobRepository jobs;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void snapshotsTargetsThenTracksCompletedItemsAndRemainingWork() {
        AdminJobDto job = jobs.create(
                AdminJobType.EMBEDDING_REBUILD,
                "EMBEDDING_CONFIGURATION",
                2L,
                null,
                Map.of());

        repository.insertBatch(job.id(), List.of(
                EmbeddingRebuildManifestItem.pending("DOCUMENT", 101L, "document-v1"),
                EmbeddingRebuildManifestItem.pending("UGC", 202L, "ugc-v1")));

        assertThat(repository.findByJobId(job.id()))
                .containsExactly(
                        EmbeddingRebuildManifestItem.pending("DOCUMENT", 101L, "document-v1"),
                        EmbeddingRebuildManifestItem.pending("UGC", 202L, "ugc-v1"));
        assertThat(repository.countItems(job.id())).isEqualTo(2);
        assertThat(repository.countIncomplete(job.id())).isEqualTo(2);

        assertThat(repository.markCompleted(job.id(), "DOCUMENT", 101L, 3, CHECKSUM_A)).isTrue();

        assertThat(repository.findByJobId(job.id()))
                .containsExactly(
                        new EmbeddingRebuildManifestItem(
                                "DOCUMENT", 101L, "document-v1", 3, CHECKSUM_A),
                        EmbeddingRebuildManifestItem.pending("UGC", 202L, "ugc-v1"));
        assertThat(repository.countIncomplete(job.id())).isEqualTo(1);
        assertThat(repository.markCompleted(job.id(), "DOCUMENT", 101L, 3, CHECKSUM_A)).isFalse();
    }

    @Test
    void rejectsDuplicateTargetsBeforeAttemptingSql() {
        List<EmbeddingRebuildManifestItem> duplicates = List.of(
                EmbeddingRebuildManifestItem.pending("DOCUMENT", 101L, "document-v1"),
                EmbeddingRebuildManifestItem.pending("DOCUMENT", 101L, "document-v2"));

        assertThatThrownBy(() -> repository.insertBatch(Long.MAX_VALUE, duplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Embedding rebuild manifest contains duplicate targets");
    }

    @Test
    void insertsManifestLargerThanPostgresqlBindParameterLimit() {
        AdminJobDto job = jobs.create(
                AdminJobType.EMBEDDING_REBUILD,
                "EMBEDDING_CONFIGURATION",
                2L,
                null,
                Map.of());
        int itemCount = 13_108;
        List<EmbeddingRebuildManifestItem> items = LongStream.rangeClosed(1, itemCount)
                .mapToObj(targetId -> EmbeddingRebuildManifestItem.pending(
                        "DOCUMENT", targetId, "document-v" + targetId))
                .toList();

        repository.insertBatch(job.id(), items);

        assertThat(repository.countItems(job.id())).isEqualTo(itemCount);
    }

    @Test
    void rollsBackEarlierBatchesWhenALaterBatchFails() {
        AdminJobDto job = jobs.create(
                AdminJobType.EMBEDDING_REBUILD,
                "EMBEDDING_CONFIGURATION",
                2L,
                null,
                Map.of());
        long conflictingTargetId = 1_001L;
        jdbcClient.sql("""
                        INSERT INTO embedding_rebuild_manifest_item (
                            job_id, target_type, target_id, content_version
                        )
                        VALUES (:jobId, 'DOCUMENT', :targetId, 'preexisting-version')
                        """)
                .param("jobId", job.id())
                .param("targetId", conflictingTargetId)
                .update();
        List<EmbeddingRebuildManifestItem> items = LongStream.rangeClosed(1, conflictingTargetId)
                .mapToObj(targetId -> EmbeddingRebuildManifestItem.pending(
                        "DOCUMENT", targetId, "document-v" + targetId))
                .toList();

        assertThatThrownBy(() -> repository.insertBatch(job.id(), items))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.countItems(job.id())).isOne();
        assertThat(repository.findByJobId(job.id()))
                .containsExactly(EmbeddingRebuildManifestItem.pending(
                        "DOCUMENT", conflictingTargetId, "preexisting-version"));
    }
}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class IngestRunSideEffectFenceIT extends DatabaseTestSupport {

    @Autowired
    private IngestRunRepository runRepository;

    @Autowired
    private IngestRunLeaseGuard guard;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void expiredAttemptCannotPublishDocumentAfterRetryOwnsTheSource() {
        SourceRecord source = sourceRepository.create(new SourceRecord(
                null, "Ingest fence", "https://ingest-fence.example.edu", "OFFICIAL",
                true, null, null));
        IngestRunLease old = runRepository.startLease(
                source.id(), "manual", "MANUAL", "worker-a");
        jdbcClient.sql("""
                        UPDATE ingest_run
                        SET heartbeat_at = now() - interval '2 hours'
                        WHERE id = :runId
                        """)
                .param("runId", old.runId())
                .update();
        runRepository.expireStale(Duration.ofHours(1));
        IngestRunLease retry = runRepository.retryFailed(old.runId(), "worker-b");
        AtomicBoolean oldPublicationEntered = new AtomicBoolean();

        assertThatThrownBy(() -> guard.inTransaction(old, operations -> {
            oldPublicationEntered.set(true);
            insertDocument(source.id(), "old-attempt");
            return null;
        })).isInstanceOf(IngestRunLeaseLostException.class);
        assertThat(oldPublicationEntered).isFalse();
        assertThat(documentCount(source.id())).isZero();

        guard.inTransaction(retry, operations -> {
            insertDocument(source.id(), "retry-attempt");
            return null;
        });
        assertThat(documentCount(source.id())).isEqualTo(1);
        assertThat(runRepository.finish(
                old, IngestRunStatus.SUCCESS, 1, 0, 0, 0, null, null)).isFalse();
        assertThat(runRepository.finish(
                retry, IngestRunStatus.SUCCESS, 1, 0, 0, 0, null, null)).isTrue();
    }

    private void insertDocument(Long sourceId, String suffix) {
        jdbcClient.sql("""
                        INSERT INTO document (source_id, title, source_url, content_hash)
                        VALUES (:sourceId, :title, :sourceUrl, :contentHash)
                        """)
                .param("sourceId", sourceId)
                .param("title", "Fence " + suffix)
                .param("sourceUrl", "https://ingest-fence.example.edu/" + suffix)
                .param("contentHash", "hash-" + suffix)
                .update();
    }

    private long documentCount(Long sourceId) {
        return jdbcClient.sql("SELECT count(*) FROM document WHERE source_id = :sourceId")
                .param("sourceId", sourceId)
                .query(Long.class)
                .single();
    }
}

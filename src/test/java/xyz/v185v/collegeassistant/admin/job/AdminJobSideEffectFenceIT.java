package xyz.v185v.collegeassistant.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class AdminJobSideEffectFenceIT extends DatabaseTestSupport {

    @Autowired
    private AdminJobRepository repository;

    @Autowired
    private AdminJobLeaseGuard guard;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void expiredAttemptCannotMutateDomainRowsAfterManualRetry() {
        SourceRecord source = sourceRepository.create(new SourceRecord(
                null, "Fence source", "https://fence.example.edu", "OFFICIAL",
                true, null, null));
        repository.create(
                AdminJobType.SOURCE_DELETE, "SOURCE", source.id(), null, Map.of());
        AdminJobLease old = repository.claimNext("worker-a").orElseThrow();
        jdbcClient.sql("""
                        UPDATE admin_job
                        SET heartbeat_at = now() - interval '2 hours'
                        WHERE id = :jobId
                        """)
                .param("jobId", old.jobId())
                .update();
        repository.expireStale(Duration.ofHours(1));
        repository.retryFailed(old.jobId());
        AdminJobLease retry = repository.claimNext("worker-b").orElseThrow();
        AtomicBoolean staleWorkEntered = new AtomicBoolean();

        assertThatThrownBy(() -> guard.inTransaction(old, operations -> {
            staleWorkEntered.set(true);
            jdbcClient.sql("UPDATE source SET enabled = false WHERE id = :sourceId")
                    .param("sourceId", source.id())
                    .update();
            return null;
        })).isInstanceOf(AdminJobLeaseLostException.class);

        assertThat(staleWorkEntered).isFalse();
        assertThat(sourceRepository.findById(source.id())).get()
                .extracting(SourceRecord::enabled)
                .isEqualTo(true);

        guard.inTransaction(retry, operations -> {
            jdbcClient.sql("UPDATE source SET enabled = false WHERE id = :sourceId")
                    .param("sourceId", source.id())
                    .update();
            return null;
        });
        assertThat(sourceRepository.findById(source.id())).get()
                .extracting(SourceRecord::enabled)
                .isEqualTo(false);
    }
}

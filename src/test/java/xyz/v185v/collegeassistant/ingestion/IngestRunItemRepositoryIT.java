package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class IngestRunItemRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private IngestRunRepository runRepository;

    @Autowired
    private IngestRunItemRepository itemRepository;

    @Autowired
    private IngestRunLeaseGuard guard;

    @Autowired
    private SourceRepository sourceRepository;

    @Test
    void checkpointAndDetailRoundTripThroughTheExactLease() {
        SourceRecord source = source("items");
        IngestRunLease lease = runRepository.startLease(
                source.id(), "manual", "MANUAL", "worker-a");
        IngestRunItemDto item = itemRepository.upsertItem(
                lease, "https://items.example.edu/plan", "URL").orElseThrow();

        guard.inTransaction(lease, operations -> {
            operations.checkpoint(
                    item.id(), IngestRunItemStatus.SKIPPED, null,
                    Map.of("reason", "UNCHANGED"));
            return null;
        });

        IngestRunDetailDto detail = runRepository.findDetailById(lease.runId()).orElseThrow();
        assertThat(detail.attempt()).isZero();
        assertThat(detail.workerId()).isEqualTo("worker-a");
        assertThat(detail.items()).singleElement().satisfies(stored -> {
            assertThat(stored.status()).isEqualTo(IngestRunItemStatus.SKIPPED);
            assertThat(stored.checkpoint()).containsEntry("reason", "UNCHANGED");
            assertThat(stored.checkpointAttempt()).isZero();
        });
    }

    @Test
    void retryCreatesNewRunFromFailedItemsAndRejectsOldToken() {
        SourceRecord source = source("retry-items");
        IngestRunLease first = runRepository.startLease(
                source.id(), "manual", "MANUAL", "worker-a");
        IngestRunItemDto succeeded = itemRepository.upsertItem(
                first, "https://retry-items.example.edu/success", "URL").orElseThrow();
        IngestRunItemDto failed = itemRepository.upsertItem(
                first, "https://retry-items.example.edu/failed", "URL").orElseThrow();
        guard.inTransaction(first, operations -> {
            operations.checkpoint(
                    succeeded.id(), IngestRunItemStatus.SUCCEEDED, null,
                    Map.of("stage", "published"));
            operations.checkpoint(
                    failed.id(), IngestRunItemStatus.FAILED, null,
                    Map.of("stage", "embedding"));
            return null;
        });
        assertThat(runRepository.finish(
                first, IngestRunStatus.PARTIAL_SUCCESS, 1, 0, 0, 1,
                "ITEM_FAILED", "one item failed")).isTrue();

        IngestRunLease retry = runRepository.retryFailed(first.runId(), "worker-b");

        assertThat(retry.runId()).isNotEqualTo(first.runId());
        assertThat(retry.attempt()).isEqualTo(1);
        assertThat(retry.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(itemRepository.findByRunId(retry.runId())).singleElement().satisfies(copied -> {
            assertThat(copied.itemKey()).endsWith("/failed");
            assertThat(copied.status()).isEqualTo(IngestRunItemStatus.QUEUED);
            assertThat(copied.retryCount()).isEqualTo(1);
            assertThat(copied.checkpoint()).containsEntry("stage", "embedding");
        });
        assertThat(itemRepository.upsertItem(first, "late", "URL")).isEmpty();
        assertThat(runRepository.heartbeat(first)).isFalse();
        assertThat(runRepository.heartbeat(retry)).isTrue();
    }

    @Test
    void discoveredUrlsAreIdempotentAndReceiveDurableRetryTimes() {
        SourceRecord source = source("remote-discovery");
        IngestRunLease lease = runRepository.startLease(
                source.id(), "scheduled", "SCHEDULED", "crawler-a");
        String canonical = "https://remote-discovery.example.edu/rules";

        IngestRunItemDto first = itemRepository.upsertDiscovered(lease.runId(), canonical)
                .orElseThrow();
        IngestRunItemDto duplicate = itemRepository.upsertDiscovered(lease.runId(), canonical)
                .orElseThrow();
        OffsetDateTime retryAt = OffsetDateTime.parse("2026-07-25T12:30:00Z");

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(itemRepository.deferUnfinished(lease.runId(), retryAt)).isEqualTo(1);
        assertThat(itemRepository.findByRunId(lease.runId())).singleElement().satisfies(item -> {
            assertThat(item.itemKey()).isEqualTo(canonical);
            assertThat(item.status()).isEqualTo(IngestRunItemStatus.FAILED);
            assertThat(item.retryCount()).isEqualTo(1);
            assertThat(item.nextAttemptAt()).isEqualTo(retryAt);
            assertThat(item.errorCode()).isEqualTo("HTTP_429");
        });
    }

    private SourceRecord source(String suffix) {
        return sourceRepository.create(new SourceRecord(
                null,
                "Ingest " + suffix,
                "https://" + suffix + ".example.edu",
                "OFFICIAL",
                true,
                null,
                null));
    }
}

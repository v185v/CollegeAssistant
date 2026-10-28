package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.util.List;

import xyz.v185v.collegeassistant.document.SourceRecord;

/** 已提交的调度器或延迟恢复认领结果，可安全提交到工作线程池。 */
public record ScheduledIngestClaim(
        SourceRecord source,
        IngestRunDto running,
        IngestRunLease lease,
        List<IngestRunItemDto> retryItems
) {

    public ScheduledIngestClaim {
        if (source == null || running == null || lease == null) {
            throw new IllegalArgumentException("Scheduled ingestion claim must be complete");
        }
        if (!source.id().equals(running.sourceId()) || !running.id().equals(lease.runId())) {
            throw new IllegalArgumentException("Scheduled ingestion claim identity does not match");
        }
        retryItems = retryItems == null ? List.of() : List.copyOf(retryItems);
    }
}

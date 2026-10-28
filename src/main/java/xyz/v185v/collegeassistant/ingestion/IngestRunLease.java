package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.util.UUID;

/** 变更或发布一次摄取运行所需的尝试作用域能力凭证。 */
public record IngestRunLease(
        Long runId,
        int attempt,
        String workerId,
        UUID leaseToken
) {
    public IngestRunLease {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must be nonblank and at most 128 characters");
        }
        if (leaseToken == null) {
            throw new IllegalArgumentException("leaseToken must not be null");
        }
    }
}

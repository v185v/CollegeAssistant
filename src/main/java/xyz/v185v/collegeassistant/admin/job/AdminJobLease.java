package xyz.v185v.collegeassistant.admin.job;

import java.util.UUID;

/** 每次运行中任务变更都必须具备的尝试作用域能力凭证。 */
public record AdminJobLease(
        Long jobId,
        int attempt,
        String workerId,
        UUID leaseToken
) {
    public AdminJobLease {
        if (jobId == null || jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive");
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

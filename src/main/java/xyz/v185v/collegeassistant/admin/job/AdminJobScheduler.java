package xyz.v185v.collegeassistant.admin.job;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在短事务中轮询持久化队列，并仅分派已提交的认领结果。 */
@Component
@ConditionalOnProperty(
        name = "app.admin.jobs.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AdminJobScheduler {

    private final AdminJobRepository repository;
    private final AdminJobDispatcher dispatcher;
    private final AdminJobService jobService;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseTimeout;

    @Autowired
    public AdminJobScheduler(
            AdminJobRepository repository,
            AdminJobDispatcher dispatcher,
            AdminJobService jobService,
            @Value("${app.admin.jobs.worker-id:}") String configuredWorkerId,
            @Value("${app.admin.jobs.claim-batch-size:8}") int batchSize,
            @Value("${app.admin.jobs.lease-timeout:PT1H}") Duration leaseTimeout
    ) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.jobService = jobService;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "admin-job-" + UUID.randomUUID()
                : configuredWorkerId;
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("admin job claim batch size must be between 1 and 100");
        }
        this.batchSize = batchSize;
        this.leaseTimeout = leaseTimeout;
    }

/** 为调度器单元测试保留的兼容构造函数。 */
    public AdminJobScheduler(
            AdminJobRepository repository,
            AdminJobDispatcher dispatcher,
            String configuredWorkerId,
            int batchSize,
            Duration leaseTimeout
    ) {
        this(repository, dispatcher, null, configuredWorkerId, batchSize, leaseTimeout);
    }

    @Scheduled(fixedDelayString = "${app.admin.jobs.poll-interval:PT5S}")
    public void dispatchQueuedJobs() {
        if (jobService == null) {
            repository.expireStale(leaseTimeout);
        } else {
            jobService.expireStale(leaseTimeout);
        }
        for (int claimed = 0; claimed < batchSize; claimed++) {
            AdminJobLease lease = repository.claimNext(workerId).orElse(null);
            if (lease == null) {
                return;
            }
            dispatcher.submit(lease);
        }
    }

    @Scheduled(fixedDelayString = "${app.admin.jobs.heartbeat-interval:PT15S}")
    public void heartbeatActiveJobs() {
        dispatcher.heartbeatActiveLeases();
    }
}

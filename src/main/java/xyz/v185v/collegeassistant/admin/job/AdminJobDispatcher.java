package xyz.v185v.collegeassistant.admin.job;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 提交已认领的持久化任务，并独立于工作线程可用性维持租约。 */
@Component
public class AdminJobDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminJobDispatcher.class);

    private final AdminJobRepository repository;
    private final AdminJobHandlerRegistry handlers;
    private final AdminJobLeaseGuard leaseGuard;
    private final AdminJobService jobService;
    private final Executor executor;
    private final Map<Long, AdminJobLease> activeLeases = new ConcurrentHashMap<>();

    @Autowired
    public AdminJobDispatcher(
            AdminJobRepository repository,
            AdminJobHandlerRegistry handlers,
            AdminJobLeaseGuard leaseGuard,
            AdminJobService jobService,
            @Qualifier("adminJobTaskExecutor") Executor executor
    ) {
        this.repository = repository;
        this.handlers = handlers;
        this.leaseGuard = leaseGuard;
        this.jobService = jobService;
        this.executor = executor;
    }

/** 为确定性调度器测试保留的兼容构造函数。 */
    public AdminJobDispatcher(
            AdminJobRepository repository,
            AdminJobHandlerRegistry handlers,
            AdminJobLeaseGuard leaseGuard,
            Executor executor
    ) {
        this(repository, handlers, leaseGuard, null, executor);
    }

    /**
* 在提交执行器前将租约加入独立心跳集合。因此，有界执行器饱和时，已认领的持久化任务
* 也不会在排队期间过期。
     */
    public boolean submit(AdminJobLease lease) {
        activeLeases.put(lease.jobId(), lease);
        try {
            executor.execute(() -> execute(lease));
            return true;
        } catch (RejectedExecutionException exception) {
            activeLeases.remove(lease.jobId(), lease);
            finish(
                    lease, AdminJobStatus.FAILED,
                    "SUBMISSION_REJECTED", "Job could not be scheduled");
            return false;
        }
    }

/** 执行一个已认领的任务；公开此方法以支持确定性执行器测试。 */
    public void execute(AdminJobLease lease) {
        try {
            Optional<AdminJobDto> job = repository.findById(lease.jobId());
            if (job.isEmpty()) {
                return;
            }
            Optional<AdminJobHandler> handler = handlers.find(job.orElseThrow().jobType());
            if (handler.isEmpty()) {
                finish(
                        lease, AdminJobStatus.FAILED,
                        "HANDLER_MISSING", "Job handler is unavailable");
                return;
            }
            handler.orElseThrow().execute(new Context(lease, job.orElseThrow()));
            finish(lease, AdminJobStatus.SUCCEEDED, null, null);
        } catch (AdminJobLeaseLostException exception) {
// 更新的尝试拥有这条记录，必须允许它独立完成。
        } catch (AdminJobPartialSuccessException exception) {
            finish(
                    lease,
                    AdminJobStatus.PARTIAL_SUCCESS,
                    exception.errorCode(),
                    exception.errorSummary());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=admin.job.failure jobId={} attempt={} causeType={}",
                    lease.jobId(),
                    lease.attempt(),
                    exception.getClass().getName());
            finish(
                    lease, AdminJobStatus.FAILED,
                    "EXECUTION_FAILED", "Job handler failed");
        } finally {
            activeLeases.remove(lease.jobId(), lease);
        }
    }

/** 由调度器线程调用，不由有界工作线程池调用。 */
    public void heartbeatActiveLeases() {
        activeLeases.forEach((jobId, lease) -> {
            if (!repository.heartbeat(lease)) {
                activeLeases.remove(jobId, lease);
            }
        });
    }

    public int activeLeaseCount() {
        return activeLeases.size();
    }

    private boolean finish(
            AdminJobLease lease,
            AdminJobStatus status,
            String errorCode,
            String errorSummary
    ) {
        if (jobService == null) {
            return repository.finish(lease, status, errorCode, errorSummary);
        }
        return jobService.finish(lease, status, errorCode, errorSummary);
    }

    private final class Context implements AdminJobExecutionContext {

        private final AdminJobLease lease;
        private final AdminJobDto job;

        private Context(AdminJobLease lease, AdminJobDto job) {
            this.lease = lease;
            this.job = job;
        }

        @Override
        public AdminJobLease lease() {
            return lease;
        }

        @Override
        public AdminJobDto job() {
            return job;
        }

        @Override
        public void assertActive() {
            leaseGuard.assertActive(lease);
        }

        @Override
        public <T> T inLeaseTransaction(Function<LeaseGuardedJobOperations, T> work) {
            return leaseGuard.inTransaction(lease, work);
        }

        @Override
        public AdminJobItemDto upsertItem(
                String itemKey,
                String itemType,
                String targetType,
                Long targetId
        ) {
            return repository.upsertItem(lease, itemKey, itemType, targetType, targetId)
                    .orElseThrow(AdminJobLeaseLostException::new);
        }

        @Override
        public void checkpoint(
                Long itemId,
                AdminJobStatus status,
                Map<String, Object> checkpoint
        ) {
            inLeaseTransaction(operations -> {
                operations.checkpoint(itemId, status, checkpoint);
                return null;
            });
        }

        @Override
        public void heartbeat() {
            if (!repository.heartbeat(lease)) {
                throw new AdminJobLeaseLostException();
            }
        }
    }
}

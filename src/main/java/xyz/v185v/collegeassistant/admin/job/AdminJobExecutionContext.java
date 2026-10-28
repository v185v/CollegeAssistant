package xyz.v185v.collegeassistant.admin.job;

import java.util.Map;
import java.util.function.Function;

/** 提供给管理任务处理器的精简租约感知接口。 */
public interface AdminJobExecutionContext {

    AdminJobLease lease();

    AdminJobDto job();

    void assertActive();

    <T> T inLeaseTransaction(Function<LeaseGuardedJobOperations, T> work);

    AdminJobItemDto upsertItem(
            String itemKey,
            String itemType,
            String targetType,
            Long targetId
    );

    void checkpoint(Long itemId, AdminJobStatus status, Map<String, Object> checkpoint);

    void heartbeat();
}

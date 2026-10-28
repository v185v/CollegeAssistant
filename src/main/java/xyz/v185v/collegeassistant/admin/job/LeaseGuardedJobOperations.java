package xyz.v185v.collegeassistant.admin.job;

import java.util.Map;

/** 确保在匹配任务记录被锁定且处于活动状态时执行的操作。 */
public interface LeaseGuardedJobOperations {

/** 在与领域副作用相同的事务中持久化条目检查点。 */
    void checkpoint(Long itemId, AdminJobStatus status, Map<String, Object> checkpoint);
}

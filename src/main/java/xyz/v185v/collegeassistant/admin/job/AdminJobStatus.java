package xyz.v185v.collegeassistant.admin.job;

/** 持久化管理任务及条目状态。 */
public enum AdminJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED;

/** @return 此状态是否可由运行中的租约写入 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == PARTIAL_SUCCESS
                || this == FAILED || this == CANCELLED;
    }

/** @return 管理员是否可以显式重试此状态 */
    public boolean isManuallyRetryable() {
        return this == FAILED || this == PARTIAL_SUCCESS;
    }
}

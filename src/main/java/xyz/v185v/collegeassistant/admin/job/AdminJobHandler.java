package xyz.v185v.collegeassistant.admin.job;

/** 一种持久化管理任务类型的处理器。 */
public interface AdminJobHandler {

    AdminJobType type();

    void execute(AdminJobExecutionContext context);
}

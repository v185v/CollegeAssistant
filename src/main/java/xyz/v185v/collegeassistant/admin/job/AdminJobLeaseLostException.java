package xyz.v185v.collegeassistant.admin.job;

/** 旧尝试不再拥有持久化任务租约时抛出。 */
public class AdminJobLeaseLostException extends RuntimeException {

    public AdminJobLeaseLostException() {
        super("Administration job lease is no longer active");
    }
}

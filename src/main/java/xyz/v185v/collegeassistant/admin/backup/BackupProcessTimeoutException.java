package xyz.v185v.collegeassistant.admin.backup;

/** 超过单调时钟定义的硬性进程截止时间。 */
public final class BackupProcessTimeoutException extends BackupProcessException {

    public BackupProcessTimeoutException() {
        super("Backup process timed out");
    }
}

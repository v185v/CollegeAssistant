package xyz.v185v.collegeassistant.admin.backup;

/** 捕获的标准输出或标准错误超过固定字节上限。 */
public final class BackupProcessOutputLimitException extends BackupProcessException {

    public BackupProcessOutputLimitException() {
        super("Backup process output limit exceeded");
    }
}

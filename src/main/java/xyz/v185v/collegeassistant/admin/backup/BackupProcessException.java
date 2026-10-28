package xyz.v185v.collegeassistant.admin.backup;

/** 已脱敏的进程监管失败；捕获的子进程输出不会进入异常消息。 */
public class BackupProcessException extends RuntimeException {

    public BackupProcessException(String message) {
        super(message);
    }

    public BackupProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}

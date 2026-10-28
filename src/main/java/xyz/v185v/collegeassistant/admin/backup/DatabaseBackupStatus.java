package xyz.v185v.collegeassistant.admin.backup;

/** 逻辑数据库备份的持久化生命周期。 */
public enum DatabaseBackupStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    VALIDATED,
    FAILED,
    DELETE_FAILED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }

    public boolean isDownloadable() {
        return this == SUCCEEDED || this == VALIDATED;
    }
}

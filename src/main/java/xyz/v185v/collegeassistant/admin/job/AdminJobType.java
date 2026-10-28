package xyz.v185v.collegeassistant.admin.job;

/** 管理控制台可用的持久化后台操作。 */
public enum AdminJobType {
    SOURCE_DELETE,
    DOCUMENT_DELETE,
    DOCUMENT_REINDEX,
    CONSISTENCY_SCAN,
    DATABASE_BACKUP,
    EMBEDDING_REBUILD,
    INDEX_CLEANUP
}

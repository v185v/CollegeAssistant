package xyz.v185v.collegeassistant.document;

/** 受管文档的持久化生命周期状态。 */
public enum DocumentStatus {
    PROCESSING,
    ACTIVE,
    FAILED,
    DELETING,
    DELETE_FAILED
}

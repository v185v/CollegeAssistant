package xyz.v185v.collegeassistant.admin.source;

/** 安全删除数据源所属文档所需的最小不可变引用。 */
public record SourceDeletionCandidate(Long documentId, String objectKey) {
}

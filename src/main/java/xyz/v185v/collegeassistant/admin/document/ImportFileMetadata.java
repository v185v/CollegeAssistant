package xyz.v185v.collegeassistant.admin.document;

/** 单个文件的客户端可见元数据；clientId 不会成为服务器文件系统路径。 */
public record ImportFileMetadata(
        String clientId,
        String displayTitle,
        String externalSourceUrl,
        Long replaceDocumentId,
        String degree,
        String college,
        String campus,
        String gradeRange
) {
}

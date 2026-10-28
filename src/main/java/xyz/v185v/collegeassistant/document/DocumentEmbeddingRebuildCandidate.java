package xyz.v185v.collegeassistant.document;

/** 为向量重建记录的不可变文档身份和内容版本。 */
public record DocumentEmbeddingRebuildCandidate(Long documentId, String contentVersion) {

    public DocumentEmbeddingRebuildCandidate {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("Document id must be positive");
        }
        if (contentVersion == null || contentVersion.isBlank()) {
            throw new IllegalArgumentException("Document content version must not be blank");
        }
    }
}

package xyz.v185v.collegeassistant.document;

import java.util.Objects;

/**
 * 文档增量写入结果。
 *
 * @param status   本次写入产生的状态
 * @param document 数据库中的最终文档
 */
public record DocumentWriteResult(DocumentWriteStatus status, DocumentRecord document) {

    public DocumentWriteResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(document, "document");
    }
}

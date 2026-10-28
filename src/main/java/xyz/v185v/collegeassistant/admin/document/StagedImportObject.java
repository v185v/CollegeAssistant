package xyz.v185v.collegeassistant.admin.document;

import java.time.Instant;

/** 暂存上传对象的不透明元数据；其键中不会出现客户端提供的文件名。 */
public record StagedImportObject(
        long runId,
        long itemId,
        long size,
        Instant createdAt,
        String relativeKey
) {
    public StagedImportObject {
        if (runId <= 0 || itemId <= 0) {
            throw new IllegalArgumentException("Staging identifiers must be positive");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Staging size must not be negative");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Staging creation time is required");
        }
        String expected = "import-staging/" + runId + "/" + itemId;
        if (!expected.equals(relativeKey)) {
            throw new IllegalArgumentException("Staging key does not match server identifiers");
        }
    }
}

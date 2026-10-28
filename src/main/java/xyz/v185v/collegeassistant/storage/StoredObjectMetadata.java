package xyz.v185v.collegeassistant.storage;

import java.time.Instant;

/** 命名空间作用域对象清单返回的不透明元数据。 */
public record StoredObjectMetadata(
        String objectKey,
        long size,
        Instant lastModified
) {
    public StoredObjectMetadata {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Object key is required");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Object size must not be negative");
        }
        if (lastModified == null) {
            throw new IllegalArgumentException("Last-modified time is required");
        }
    }
}

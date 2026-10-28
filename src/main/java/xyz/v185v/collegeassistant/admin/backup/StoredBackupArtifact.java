package xyz.v185v.collegeassistant.admin.backup;

/** 已发布归档的不可变元数据。 */
public record StoredBackupArtifact(
        String objectKey,
        long sizeBytes,
        String sha256
) {

    public StoredBackupArtifact {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Backup object key is required");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Backup size must not be negative");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Backup checksum must be lowercase SHA-256");
        }
    }
}

package xyz.v185v.collegeassistant.admin.model;

import java.util.Locale;
import java.util.Set;

/** 用于校验候选向量重建目标的不可变快照记录。 */
public record EmbeddingRebuildManifestItem(
        String targetType,
        long targetId,
        String contentVersion,
        int chunkCount,
        String chunkTextChecksum
) {

    public static final int INCOMPLETE_CHUNK_COUNT = -1;

    private static final Set<String> TARGET_TYPES = Set.of("DOCUMENT", "UGC");

    public EmbeddingRebuildManifestItem {
        targetType = required(targetType, "targetType").toUpperCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(targetType)) {
            throw new IllegalArgumentException("Embedding rebuild targetType is invalid");
        }
        if (targetId <= 0) {
            throw new IllegalArgumentException("Embedding rebuild targetId must be positive");
        }
        contentVersion = required(contentVersion, "contentVersion");
        if (contentVersion.length() > 128) {
            throw new IllegalArgumentException(
                    "Embedding rebuild contentVersion must not exceed 128 characters");
        }
        chunkTextChecksum = chunkTextChecksum == null ? null : chunkTextChecksum.strip();
        if (chunkCount == INCOMPLETE_CHUNK_COUNT) {
            if (chunkTextChecksum != null) {
                throw new IllegalArgumentException(
                        "Incomplete embedding rebuild items must not have a checksum");
            }
        } else {
            if (chunkCount < 0) {
                throw new IllegalArgumentException("Embedding rebuild chunkCount must not be negative");
            }
            if (chunkTextChecksum == null || !chunkTextChecksum.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Embedding rebuild checksum must be lowercase SHA-256");
            }
        }
    }

    public static EmbeddingRebuildManifestItem pending(
            String targetType,
            long targetId,
            String contentVersion
    ) {
        return new EmbeddingRebuildManifestItem(
                targetType, targetId, contentVersion, INCOMPLETE_CHUNK_COUNT, null);
    }

    public boolean isComplete() {
        return chunkCount != INCOMPLETE_CHUNK_COUNT;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Embedding rebuild " + field + " must not be blank");
        }
        return value.strip();
    }
}

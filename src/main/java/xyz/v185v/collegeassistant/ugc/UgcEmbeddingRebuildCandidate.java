package xyz.v185v.collegeassistant.ugc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Objects;

/** 为向量重建记录的已审核 UGC 内容和稳定版本。 */
public record UgcEmbeddingRebuildCandidate(UgcPostDto post, String contentVersion) {

    public UgcEmbeddingRebuildCandidate {
        post = Objects.requireNonNull(post, "post");
        if (post.id() == null || post.id() <= 0) {
            throw new IllegalArgumentException("UGC post id must be positive");
        }
        if (post.status() != UgcStatus.APPROVED) {
            throw new IllegalArgumentException("UGC rebuild candidates must be approved");
        }
        if (contentVersion == null || contentVersion.isBlank()) {
            throw new IllegalArgumentException("UGC content version must not be blank");
        }
    }

    public Long postId() {
        return post.id();
    }

    static String contentVersion(
            OffsetDateTime updatedAt,
            String title,
            String content,
            String tagsJson,
            String relatedCourse,
            String relatedLocation
    ) {
        if (updatedAt == null) {
            throw new IllegalArgumentException("UGC updatedAt must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            appendField(digest, updatedAt.toInstant().toString());
            appendField(digest, title);
            appendField(digest, content);
            appendField(digest, tagsJson);
            appendField(digest, relatedCourse);
            appendField(digest, relatedLocation);
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendField(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(Character.forDigit((current >>> 4) & 0xf, 16));
            value.append(Character.forDigit(current & 0xf, 16));
        }
        return value.toString();
    }
}

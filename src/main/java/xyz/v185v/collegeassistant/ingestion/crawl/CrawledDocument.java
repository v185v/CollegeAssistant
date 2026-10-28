package xyz.v185v.collegeassistant.ingestion.crawl;

import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 从外部数据源取得的原始文档。
 *
 * @param sourceUrl   文档来源地址
 * @param filename    建议文件名
 * @param contentType HTTP 媒体类型
 * @param lastModified 来源声明的最后修改时间
 * @param rawBytes    原始内容；构造和读取时均执行防御性复制
 */
public record CrawledDocument(
        String sourceUrl,
        String filename,
        String contentType,
        String etag,
        OffsetDateTime lastModified,
        String contentHash,
        byte[] rawBytes,
        boolean conditionalMetadata) {

    public static final int MAX_RAW_BYTES = 20 * 1024 * 1024;

/** 为现有调用方保留的兼容构造函数；成功响应正文始终会生成摘要。 */
    public CrawledDocument(
            String sourceUrl,
            String filename,
            String contentType,
            OffsetDateTime lastModified,
            byte[] rawBytes
    ) {
        this(sourceUrl, filename, contentType, null, lastModified, sha256(rawBytes), rawBytes, false);
    }

/** 受保护 HTTP 抓取使用的带校验器构造函数。 */
    public CrawledDocument(
            String sourceUrl,
            String filename,
            String contentType,
            String etag,
            OffsetDateTime lastModified,
            String contentHash,
            byte[] rawBytes
    ) {
        this(sourceUrl, filename, contentType, etag, lastModified, contentHash, rawBytes, true);
    }

    public CrawledDocument {
        sourceUrl = requireText(sourceUrl, "sourceUrl");
        filename = requireText(filename, "filename");
        contentType = requireText(contentType, "contentType");
        if (rawBytes == null) {
            throw new IllegalArgumentException("rawBytes must not be null");
        }
        if (rawBytes.length > MAX_RAW_BYTES) {
            throw new IllegalArgumentException("rawBytes exceeds maximum size");
        }
        if (etag != null) {
            etag = etag.strip();
            if (etag.isEmpty()) {
                etag = null;
            } else if (etag.length() > 512 || etag.indexOf('\r') >= 0 || etag.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("etag is invalid");
            }
        }
        if (contentHash == null && rawBytes.length != 0) {
            throw new IllegalArgumentException("Only a bodyless 304 may omit contentHash");
        }
        if (contentHash != null
                && (!contentHash.matches("[0-9a-f]{64}")
                || !contentHash.equals(sha256(rawBytes)))) {
            throw new IllegalArgumentException("contentHash is invalid");
        }
        rawBytes = rawBytes.clone();
    }

    public static CrawledDocument notModified(
            String sourceUrl,
            String filename,
            String contentType,
            String etag,
            OffsetDateTime lastModified
    ) {
        return new CrawledDocument(
                sourceUrl, filename, contentType, etag, lastModified, null, new byte[0], true);
    }

/** 空内容摘要仅用于从未打开响应正文的 304 响应。 */
    public boolean notModified() {
        return contentHash == null;
    }

    /** @return 原始内容的防御性副本 */
    @Override
    public byte[] rawBytes() {
        return rawBytes.clone();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static String sha256(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("rawBytes must not be null");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

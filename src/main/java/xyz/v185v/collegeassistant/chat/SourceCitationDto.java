package xyz.v185v.collegeassistant.chat;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 返回客户端并随助手消息持久化的来源引用。
 *
 * @param chunkId       检索分块编号
 * @param sourceType    官方或 UGC 来源
 * @param title         来源标题；官方来源必填
 * @param sourceUrl     安全的 HTTP(S) 地址；官方来源必填
 * @param sourceOrg     来源机构
 * @param publishedAt   发布时间
 * @param relevanceScore 相关性得分
 * @param quote         有长度上限的证据摘录
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SourceCitationDto(
        Long chunkId,
        SourceType sourceType,
        String title,
        String sourceUrl,
        String sourceOrg,
        OffsetDateTime publishedAt,
        Double relevanceScore,
        String quote,
        CitationReferenceType referenceType,
        Long documentId,
        String externalSourceUrl
) {

/** 保留稳定引用引入前持久化的引用记录。 */
    public SourceCitationDto(
            Long chunkId,
            SourceType sourceType,
            String title,
            String sourceUrl,
            String sourceOrg,
            OffsetDateTime publishedAt,
            Double relevanceScore,
            String quote
    ) {
        this(
                chunkId,
                sourceType,
                title,
                sourceUrl,
                sourceOrg,
                publishedAt,
                relevanceScore,
                quote,
                sourceType == SourceType.UGC
                        ? CitationReferenceType.UGC
                        : CitationReferenceType.EXTERNAL,
                null,
                null);
    }

    public SourceCitationDto {
        if (chunkId == null || chunkId <= 0) {
            throw new IllegalArgumentException("Citation chunkId must be positive");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("Citation sourceType must not be null");
        }
        if (sourceType == SourceType.OFFICIAL
                && (title == null || title.isBlank())) {
            throw new IllegalArgumentException(
                    "Official citation title must not be blank");
        }
        requireCodePointLimit(
                title,
                ChatConstraints.MAX_SOURCE_TITLE_CODE_POINTS,
                "Citation title must not exceed 512 characters");
        requireCodePointLimit(
                sourceOrg,
                ChatConstraints.MAX_SOURCE_ORG_CODE_POINTS,
                "Citation source organization must not exceed 128 characters");
        requireCodePointLimit(
                sourceUrl,
                ChatConstraints.MAX_SOURCE_URL_CODE_POINTS,
                "Citation URL must not exceed 4096 characters");
        requireCodePointLimit(
                externalSourceUrl,
                ChatConstraints.MAX_SOURCE_URL_CODE_POINTS,
                "Citation external source URL must not exceed 4096 characters");
        if (sourceUrl != null) {
            requireSafeUrl(sourceUrl);
        }
        if (externalSourceUrl != null) {
            requireSafeUrl(externalSourceUrl);
        }
        if (relevanceScore != null
                && (!Double.isFinite(relevanceScore)
                || relevanceScore < 0.0
                || relevanceScore > 1.0)) {
            throw new IllegalArgumentException(
                    "Citation relevance score must be finite and between zero and one");
        }
        if (quote != null && quote.codePointCount(0, quote.length())
                > ChatConstraints.MAX_QUOTE_CODE_POINTS) {
            throw new IllegalArgumentException("Citation quote must not exceed 500 characters");
        }
        referenceType = referenceType == null
                ? defaultReferenceType(sourceType)
                : referenceType;
        if (sourceType == SourceType.OFFICIAL
                && referenceType != CitationReferenceType.EXTERNAL
                && referenceType != CitationReferenceType.INTERNAL) {
            throw new IllegalArgumentException(
                    "Official citations must be external or internal");
        }
        if (sourceType == SourceType.UGC && referenceType != CitationReferenceType.UGC) {
            throw new IllegalArgumentException("UGC citations must use the UGC reference type");
        }
        if (referenceType == CitationReferenceType.INTERNAL) {
            if (documentId == null || documentId <= 0) {
                throw new IllegalArgumentException(
                        "Internal citations require a positive documentId");
            }
            if (sourceUrl != null) {
                throw new IllegalArgumentException(
                        "Internal citations must not expose a source URL");
            }
        } else {
            if (documentId != null) {
                throw new IllegalArgumentException(
                        "Only internal citations may contain a documentId");
            }
            if (externalSourceUrl != null) {
                throw new IllegalArgumentException(
                        "Only internal citations may contain an external source URL");
            }
            if (referenceType == CitationReferenceType.EXTERNAL
                    && (sourceUrl == null || sourceUrl.isBlank())) {
                throw new IllegalArgumentException("External citation URL must not be blank");
            }
        }
    }

    private static CitationReferenceType defaultReferenceType(SourceType sourceType) {
        return sourceType == SourceType.UGC
                ? CitationReferenceType.UGC
                : CitationReferenceType.EXTERNAL;
    }

    private static void requireCodePointLimit(
            String value,
            int maximum,
            String message
    ) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireSafeUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);
            if (!uri.isAbsolute() || !http || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException(
                        "Citation URL must be an absolute HTTP(S) URL without credentials");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "Citation URL must be an absolute HTTP(S) URL without credentials");
        }
    }
}

package xyz.v185v.collegeassistant.ingestion.parsing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * 按字符上限和重叠窗口切分文档文本。
 *
 * <p>切分优先选择自然边界，并保证不会截断 Unicode 代理对。</p>
 */
@Component
public final class TextChunker {

    public static final int DEFAULT_MAX_CHARS = 1200;
    public static final int DEFAULT_OVERLAP_CHARS = 150;

    private final int maxChars;
    private final int overlapChars;

    public TextChunker() {
        this(DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    public TextChunker(int maxChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars must be between zero and maxChars - 1");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    /**
     * 将非空文本切分为有序、可追溯到原文偏移的分块。
     *
     * @param text 原始文档文本
     * @return 不可变分块列表
     */
    public List<TextChunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.INGEST_FAILED, "Document text must not be blank");
        }

        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = safeEnd(text, start, Math.min(text.length(), start + maxChars));
            if (hardEnd <= start) {
                throw new BusinessException(
                        ErrorCode.INGEST_FAILED,
                        "Configured chunk size cannot preserve a Unicode code point");
            }
            int end = hardEnd == text.length()
                    ? hardEnd
                    : preferredEnd(text, start, hardEnd);
            if (end <= start) {
                end = hardEnd;
            }

            String chunkText = text.substring(start, end);
            if (!chunkText.isBlank()) {
                chunks.add(new TextChunk(chunks.size(), chunkText, start, end));
            }
            if (end == text.length()) {
                break;
            }

            int nextStart = overlapChars == 0
                    ? end
                    : Math.max(start + 1, end - overlapChars);
            nextStart = safeStart(text, nextStart);
            if (nextStart <= start) {
                nextStart = safeStart(text, start + 1);
            }
            start = nextStart;
        }

        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.INGEST_FAILED, "Document text contains no content");
        }
        return List.copyOf(chunks);
    }

    private int preferredEnd(String text, int start, int hardEnd) {
        int minimumBoundary = start + Math.max(1, (hardEnd - start) / 2);

        int paragraph = text.lastIndexOf("\n\n", hardEnd - 2);
        if (paragraph >= minimumBoundary) {
            return paragraph + 2;
        }

        for (int index = hardEnd - 1; index >= minimumBoundary; index--) {
            if (isSentenceBoundary(text.charAt(index))) {
                return index + 1;
            }
        }
        for (int index = hardEnd - 1; index >= minimumBoundary; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index + 1;
            }
        }
        return hardEnd;
    }

    private boolean isSentenceBoundary(char value) {
        return value == '。' || value == '！' || value == '？'
                || value == '.' || value == '!' || value == '?';
    }

    private int safeEnd(String text, int start, int candidate) {
        if (candidate > start && candidate < text.length()
                && Character.isHighSurrogate(text.charAt(candidate - 1))
                && Character.isLowSurrogate(text.charAt(candidate))) {
            return candidate - 1;
        }
        return candidate;
    }

    private int safeStart(String text, int candidate) {
        if (candidate > 0 && candidate < text.length()
                && Character.isHighSurrogate(text.charAt(candidate - 1))
                && Character.isLowSurrogate(text.charAt(candidate))) {
            return candidate + 1;
        }
        return candidate;
    }
}

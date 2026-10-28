package xyz.v185v.collegeassistant.ingestion.parsing;

/**
 * 文档文本的一个连续分块。
 *
 * @param index       分块序号
 * @param text        分块文本
 * @param startOffset 在原文中的起始字符偏移
 * @param endOffset   在原文中的结束字符偏移，不含该位置
 */
public record TextChunk(int index, String text, int startOffset, int endOffset) {

    public TextChunk {
        if (index < 0) {
            throw new IllegalArgumentException("Chunk index must not be negative");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chunk text must not be blank");
        }
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("Chunk offsets must describe a non-empty range");
        }
        if (endOffset - startOffset != text.length()) {
            throw new IllegalArgumentException("Chunk offsets must match chunk text length");
        }
    }
}

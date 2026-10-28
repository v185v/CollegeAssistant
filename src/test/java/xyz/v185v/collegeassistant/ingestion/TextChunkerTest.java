package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.parsing.TextChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class TextChunkerTest {

    @Test
    void shortTextProducesOneChunkWithSourceOffsets() {
        String text = "这是一段短文本。";

        List<TextChunk> chunks = new TextChunker(1200, 150).chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().index()).isZero();
        assertThat(chunks.getFirst().text()).isEqualTo(text);
        assertThat(chunks.getFirst().startOffset()).isZero();
        assertThat(chunks.getFirst().endOffset()).isEqualTo(text.length());
    }

    @Test
    void longTextProducesOrderedNonEmptyChunksWithConfiguredOverlap() {
        String text = "第一段说明选课规则。\n\n"
                + "第二段说明退课规则，学生应在截止日期前完成操作。\n\n"
                + "第三段说明咨询方式，请联系教务处。";
        int maxChars = 32;
        int overlapChars = 8;

        List<TextChunk> chunks = new TextChunker(maxChars, overlapChars).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text()).isNotBlank();
            assertThat(chunk.text().length()).isLessThanOrEqualTo(maxChars);
            assertThat(chunk.text()).isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
        });
        assertThat(chunks).extracting(TextChunk::index)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        for (int index = 1; index < chunks.size(); index++) {
            TextChunk previous = chunks.get(index - 1);
            TextChunk current = chunks.get(index);
            assertThat(current.startOffset()).isLessThan(previous.endOffset());
            assertThat(previous.endOffset() - current.startOffset()).isLessThanOrEqualTo(overlapChars);
        }
    }

    @Test
    void prefersParagraphSentenceOrWhitespaceBoundariesWhenAvailable() {
        String text = "第一句说明课程安排。第二句说明报名时间。\n\n"
                + "另一段说明退课流程。最后一句说明咨询渠道。";

        List<TextChunk> chunks = new TextChunker(24, 4).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.subList(0, chunks.size() - 1))
                .anySatisfy(chunk -> assertThat(isSafeBoundary(
                        chunk.text().charAt(chunk.text().length() - 1))).isTrue());
    }

    @Test
    void paragraphDelimiterCrossingTheHardEndNeverExceedsMaximum() {
        String text = "123456789\n\nremaining text";

        List<TextChunk> chunks = new TextChunker(10, 2).chunk(text);

        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text().length()).isLessThanOrEqualTo(10));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void textWithoutSeparatorsStillTerminatesAndRespectsMaximum() {
        String text = "字".repeat(5000);

        List<TextChunk> chunks = new TextChunker(100, 20).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text().length()).isLessThanOrEqualTo(100));
    }

    @Test
    void constructorRequiresPositiveMaximumAndValidOverlap() {
        assertThatThrownBy(() -> new TextChunker(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(10, 11))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAndBlankInputFailWithIngestCode() {
        assertThatThrownBy(() -> new TextChunker().chunk(null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED));
        assertThatThrownBy(() -> new TextChunker().chunk(" \n\t "))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED));
    }

    @Test
    void outputIsImmutableAndChunkingIsDeterministic() {
        String text = "招生简章第一部分。招生简章第二部分。招生简章第三部分。";
        TextChunker chunker = new TextChunker(18, 3);

        List<TextChunk> first = chunker.chunk(text);
        List<TextChunk> second = chunker.chunk(text);

        assertThat(second).containsExactlyElementsOf(first);
        assertThatThrownBy(() -> first.add(first.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chineseTextAndSurrogatePairsAreNeverSplitAtOffsets() {
        String text = "中文通知😀😀😀😀😀，请阅读后完成确认。".repeat(20);

        List<TextChunk> chunks = new TextChunker(30, 5).chunk(text);

        for (TextChunk chunk : chunks) {
            assertThat(chunk.text()).isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
            assertThat(chunk.startOffset()).isGreaterThanOrEqualTo(0);
            assertThat(chunk.endOffset()).isLessThanOrEqualTo(text.length());
            if (chunk.startOffset() > 0) {
                assertThat(Character.isLowSurrogate(text.charAt(chunk.startOffset()))
                        && Character.isHighSurrogate(text.charAt(chunk.startOffset() - 1))).isFalse();
            }
            if (chunk.endOffset() < text.length()) {
                assertThat(Character.isHighSurrogate(text.charAt(chunk.endOffset() - 1))
                        && Character.isLowSurrogate(text.charAt(chunk.endOffset()))).isFalse();
            }
        }
    }

    private boolean isSafeBoundary(char value) {
        return Character.isWhitespace(value)
                || value == '。' || value == '！' || value == '？'
                || value == '.' || value == '!' || value == '?';
    }

}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.TikaDocumentParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class TikaDocumentParserTest {

    private final DocumentParser parser = new TikaDocumentParser();

    @Test
    void extractsUtf8HtmlTitleAndVisibleBodyOnly() {
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                  <head>
                    <meta charset="UTF-8">
                    <title>选课通知</title>
                    <style>.secret-style { content: '不得进入正文'; }</style>
                    <script>window.secretScript = '不得进入正文';</script>
                  </head>
                  <body>
                    <h1>秋季选课安排</h1>
                    <p>第一轮选课将于九月一日开始。</p>
                  </body>
                </html>
                """;

        ParsedDocument document = parser.parse(stream(html), "notices/course-selection.html");

        assertThat(document.title()).isEqualTo("选课通知");
        assertThat(document.text())
                .contains("秋季选课安排", "第一轮选课将于九月一日开始。")
                .doesNotContain("secretScript", "secret-style", "不得进入正文");
        assertThat(document.mediaType()).startsWith("text/html");
    }

    @Test
    void extractsChinesePlainTextAndUsesFilenameWithoutExtensionAsTitle() {
        String text = "教务处通知\n请同学们按时完成学籍确认。";

        ParsedDocument document = parser.parse(stream(text), "uploads/校园通知.txt");

        assertThat(document.title()).isEqualTo("校园通知");
        assertThat(document.text()).contains("教务处通知", "请同学们按时完成学籍确认。");
        assertThat(document.mediaType()).startsWith("text/plain");
    }

    @Test
    void fallsBackToWindowsResourceFilenameWhenDocumentHasNoTitle() {
        ParsedDocument document = parser.parse(
                stream("<html><body><p>Fallback body</p></body></html>"),
                "C:\\incoming\\fallback-note.html");

        assertThat(document.title()).isEqualTo("fallback-note");
        assertThat(document.text()).contains("Fallback body");
    }

    @Test
    void emptyAndWhitespaceOnlyInputsFailWithIngestCode() {
        assertIngestFailure(new ByteArrayInputStream(new byte[0]), "empty.txt");
        assertIngestFailure(stream(" \n\t\r\n "), "blank.txt");
    }

    @Test
    void readFailuresAreWrappedWithoutLeakingInputContent() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated-source-failure");
            }
        };

        assertThatThrownBy(() -> parser.parse(broken, "broken.bin"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("simulated-source-failure");
                });
    }

    @Test
    void extractionLimitFailureUsesIngestCodeWithoutLeakingBody() {
        String sensitiveBody = "PRIVATE-STUDENT-CONTENT-" + "x".repeat(128);
        DocumentParser limitedParser = new TikaDocumentParser(32);

        assertThatThrownBy(() -> limitedParser.parse(stream(sensitiveBody), "private.txt"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("PRIVATE-STUDENT-CONTENT");
                });
    }

    @Test
    void extractionLimitMustBePositive() {
        assertThatThrownBy(() -> new TikaDocumentParser(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TikaDocumentParser(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotCloseCallerOwnedInputStream() {
        TrackingInputStream input = new TrackingInputStream(
                "Caller still owns this stream.".getBytes(StandardCharsets.UTF_8));

        parser.parse(input, "owned.txt");

        assertThat(input.closed()).isFalse();
    }

    @Test
    void parsedDocumentDefensivelyCopiesMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("author", "Registrar");

        ParsedDocument document = new ParsedDocument(
                "Title", "Body", "text/plain", metadata);
        metadata.put("author", "Mutated");

        assertThat(document.metadata()).containsEntry("author", "Registrar");
        assertThatThrownBy(() -> document.metadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void assertIngestFailure(InputStream input, String resourceName) {
        assertThatThrownBy(() -> parser.parse(input, resourceName))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED));
    }

    private ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }
}

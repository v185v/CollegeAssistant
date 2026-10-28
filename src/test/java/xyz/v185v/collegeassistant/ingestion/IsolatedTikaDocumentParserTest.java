package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.IsolatedTikaDocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.ParserWorkerCommandFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class IsolatedTikaDocumentParserTest {

    @Test
    void canBeConstructedByTheSpringContainer() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(
                    ApplicationConversionService.getSharedInstance());
            context.register(ParserWorkerCommandFactory.class, IsolatedTikaDocumentParser.class);
            context.refresh();

            assertThat(context.getBean(DocumentParser.class))
                    .isInstanceOf(IsolatedTikaDocumentParser.class);
        }
    }

    @Test
    void parsesTextInASeparateJvm() {
        IsolatedTikaDocumentParser parser = new IsolatedTikaDocumentParser(
                new ParserWorkerCommandFactory(), Duration.ofSeconds(20),
                20 * 1024 * 1024, 24 * 1024 * 1024);

        ParsedDocument parsed = parser.parse(
                new ByteArrayInputStream("Campus handbook body".getBytes(StandardCharsets.UTF_8)),
                "handbook.txt");

        assertThat(parsed.title()).isEqualTo("handbook");
        assertThat(parsed.text()).contains("Campus handbook body");
        assertThat(parsed.mediaType()).startsWith("text/plain");
    }

    @Test
    void timesOutAndTerminatesTheWorker() {
        ParserWorkerCommandFactory factory = new ParserWorkerCommandFactory() {
            @Override
            public WorkerCommand create(String resourceName) {
                String java = Path.of(
                        System.getProperty("java.home"), "bin",
                        isWindows() ? "java.exe" : "java").toString();
                return new WorkerCommand(
                        List.of(java, "-cp", testClasspath(), SlowWorker.class.getName()),
                        Map.of("LANG", "C", "LC_ALL", "C"));
            }
        };
        IsolatedTikaDocumentParser parser = new IsolatedTikaDocumentParser(
                factory, Duration.ofMillis(150), 1024, 1024);

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(new byte[] {1}), "slow.txt"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INGEST_FAILED));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String testClasspath() {
        return System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
    }

    public static final class SlowWorker {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000);
        }
    }
}

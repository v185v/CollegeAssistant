package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.parsing.ParserWorkerCommandFactory;
import xyz.v185v.collegeassistant.ingestion.parsing.TikaParserWorkerMain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ParserWorkerCommandFactoryTest {

    @Test
    void developmentCommandUsesRuntimeClasspathAndMinimalEnvironment() {
        ParserWorkerCommandFactory factory = new ParserWorkerCommandFactory(
                Path.of("C:/jdk"), "classes;dependencies/*", null, false,
                Map.of(
                        "PATH", "secret-path",
                        "HOME", "secret-home",
                        "HTTP_PROXY", "secret-proxy",
                        "AWS_SECRET_ACCESS_KEY", "secret-cloud",
                        "JWT_SECRET", "secret-app",
                        "SystemRoot", "C:/Windows"));

        ParserWorkerCommandFactory.WorkerCommand worker = factory.create("catalog.pdf");

        assertThat(worker.command()).containsSubsequence(
                "-Xmx256m", "-cp", "classes;dependencies/*",
                TikaParserWorkerMain.class.getName());
        assertThat(worker.command()).doesNotContain("secret-path", "secret-home");
        assertThat(worker.environment()).containsEntry("LANG", "C")
                .containsEntry("LC_ALL", "C")
                .containsEntry("SystemRoot", "C:/Windows")
                .doesNotContainKeys("PATH", "HOME", "HTTP_PROXY",
                        "AWS_SECRET_ACCESS_KEY", "JWT_SECRET");
    }

    @Test
    void packagedCommandUsesPropertiesLauncherAndApplicationJar() {
        Path jar = Path.of("C:/app/college-assistant.jar");
        ParserWorkerCommandFactory factory = new ParserWorkerCommandFactory(
                Path.of("C:/jdk"), "ignored", jar, true, Map.of());

        ParserWorkerCommandFactory.WorkerCommand worker = factory.create("rules.docx");

        assertThat(worker.command()).containsSubsequence(
                "-Xmx256m",
                "-Dloader.main=" + TikaParserWorkerMain.class.getName(),
                "-cp", jar.toString(),
                ParserWorkerCommandFactory.PROPERTIES_LAUNCHER);
        assertThat(worker.command()).doesNotContain("ignored");
    }
}

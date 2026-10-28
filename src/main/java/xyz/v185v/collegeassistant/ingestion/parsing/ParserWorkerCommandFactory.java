package xyz.v185v.collegeassistant.ingestion.parsing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 构建不经过 shell 的解析器工作命令和明确的环境允许列表。 */
@Component
public class ParserWorkerCommandFactory {

    public static final String PROPERTIES_LAUNCHER =
            "org.springframework.boot.loader.launch.PropertiesLauncher";

    private final Path javaHome;
    private final String runtimeClasspath;
    private final Path applicationJar;
    private final boolean packagedJar;
    private final Map<String, String> inheritedEnvironment;

    public ParserWorkerCommandFactory() {
        this(defaultJavaHome(), defaultClasspath(), codeSourceJar(),
                codeSourceJar() != null, System.getenv());
    }

    public ParserWorkerCommandFactory(
            Path javaHome,
            String runtimeClasspath,
            Path applicationJar,
            boolean packagedJar,
            Map<String, String> inheritedEnvironment
    ) {
        if (javaHome == null || runtimeClasspath == null || inheritedEnvironment == null) {
            throw new IllegalArgumentException("Parser worker command configuration is incomplete");
        }
        if (packagedJar && applicationJar == null) {
            throw new IllegalArgumentException("Packaged parser worker requires an application jar");
        }
        this.javaHome = javaHome;
        this.runtimeClasspath = runtimeClasspath;
        this.applicationJar = applicationJar;
        this.packagedJar = packagedJar;
        this.inheritedEnvironment = Map.copyOf(inheritedEnvironment);
    }

    public WorkerCommand create(String resourceName) {
        String encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(
                normalizedResourceName(resourceName).getBytes(StandardCharsets.UTF_8));
        List<String> command = new ArrayList<>();
        command.add(javaBinary().toString());
        command.add("-Xmx256m");
        if (packagedJar) {
            command.add("-Dloader.main=" + TikaParserWorkerMain.class.getName());
            command.add("-cp");
            command.add(applicationJar.toString());
            command.add(PROPERTIES_LAUNCHER);
        } else {
            command.add("-cp");
            command.add(runtimeClasspath);
            command.add(TikaParserWorkerMain.class.getName());
        }
        command.add("--resource-name-base64=" + encodedName);
        return new WorkerCommand(command, minimalEnvironment());
    }

    public ProcessBuilder processBuilder(String resourceName) {
        WorkerCommand worker = create(resourceName);
        ProcessBuilder builder = new ProcessBuilder(worker.command());
        builder.redirectErrorStream(false);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(worker.environment());
        return builder;
    }

    private Path javaBinary() {
        return javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    }

    private Map<String, String> minimalEnvironment() {
        Map<String, String> result = new LinkedHashMap<>();
        copyCaseInsensitive(result, "SystemRoot");
        copyCaseInsensitive(result, "WINDIR");
        result.put("LANG", "C");
        result.put("LC_ALL", "C");
        return Map.copyOf(result);
    }

    private void copyCaseInsensitive(Map<String, String> target, String requestedKey) {
        inheritedEnvironment.forEach((key, value) -> {
            if (key.equalsIgnoreCase(requestedKey) && value != null && !value.isBlank()) {
                target.put(requestedKey, value);
            }
        });
    }

    private String normalizedResourceName(String resourceName) {
        if (resourceName == null || resourceName.isBlank() || resourceName.length() > 512
                || resourceName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Parser resource name is invalid");
        }
        return resourceName;
    }

    private static Path defaultJavaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    private static String defaultClasspath() {
        return System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path", ""));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path codeSourceJar() {
        try {
            URI location = ParserWorkerCommandFactory.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path path = Path.of(location).toAbsolutePath().normalize();
            return path.getFileName() != null
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    ? path : null;
        } catch (Exception exception) {
            return null;
        }
    }

    public record WorkerCommand(List<String> command, Map<String, String> environment) {
        public WorkerCommand {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
        }
    }
}

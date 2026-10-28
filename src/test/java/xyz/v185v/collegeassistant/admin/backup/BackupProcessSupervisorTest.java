package xyz.v185v.collegeassistant.admin.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupProcessSupervisorTest {

    @TempDir
    Path tempDir;

    private final BackupProcessSupervisor supervisor = new BackupProcessSupervisor();

    @Test
    void capturesBothStreamsBeforeReturningACompletedProcess() {
        SupervisedProcessResult result = supervisor.run(
                builder("normal", tempDir.resolve("unused.pid")),
                Duration.ofSeconds(5),
                1024);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().utf8()).contains("fixture-stdout");
        assertThat(result.stderr().utf8()).contains("fixture-stderr");
    }

    @Test
    void timeoutKillsTheProcessTreeAndDoesNotWaitForPipeEof() throws Exception {
        Path childPid = tempDir.resolve("timeout-child.pid");

        // 给独立 JVM 固件启动父子进程留出时间，随后仍须超时并终止整棵进程树。
        assertThatThrownBy(() -> supervisor.run(
                builder("hang", childPid), Duration.ofSeconds(5), 1024))
                .isInstanceOf(BackupProcessTimeoutException.class);

        assertProcessStopped(readPid(childPid));
    }

    @Test
    void outputOverflowKillsTheProcessTreeWhileItIsStillWriting() throws Exception {
        Path childPid = tempDir.resolve("overflow-child.pid");

        assertThatThrownBy(() -> supervisor.run(
                builder("overflow", childPid), Duration.ofSeconds(5), 128))
                .isInstanceOf(BackupProcessOutputLimitException.class);

        assertProcessStopped(readPid(childPid));
    }

    private ProcessBuilder builder(String mode, Path pidFile) {
        return new ProcessBuilder(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                BackupProcessFixture.class.getName(),
                mode,
                pidFile.toString()));
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private long readPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (Files.notExists(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(pidFile).exists();
        return Long.parseLong(Files.readString(pidFile).strip());
    }

    private void assertProcessStopped(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}

/** 独立 JVM 固件，使监管器测试能够覆盖真实进程和管道行为。 */
final class BackupProcessFixture {

    private BackupProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path pidFile = Path.of(args[1]);
        if ("normal".equals(mode)) {
            System.out.println("fixture-stdout");
            System.err.println("fixture-stderr");
            return;
        }
        if ("sleep".equals(mode)) {
            Thread.sleep(Duration.ofMinutes(5));
            return;
        }
        Process child = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                BackupProcessFixture.class.getName(),
                "sleep",
                pidFile.toString())
                .inheritIO()
                .start();
        Files.writeString(pidFile, Long.toString(child.pid()));
        if ("overflow".equals(mode)) {
            byte[] chunk = new byte[4096];
            while (true) {
                System.out.write(chunk);
                System.out.flush();
            }
        }
        Thread.sleep(Duration.ofMinutes(5));
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}

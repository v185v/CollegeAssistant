package xyz.v185v.collegeassistant.admin.backup;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 持有子进程及其两个输出读取器，直到所有资源都进入终态。 */
@Component
public class BackupProcessSupervisor {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);
    private static final Duration DEFAULT_KILL_GRACE = Duration.ofSeconds(2);

    private final Duration killGrace;

    public BackupProcessSupervisor() {
        this(DEFAULT_KILL_GRACE);
    }

    @Autowired
    public BackupProcessSupervisor(BackupProperties properties) {
        this(properties.killGrace());
    }

    BackupProcessSupervisor(Duration killGrace) {
        if (killGrace == null || killGrace.isZero() || killGrace.isNegative()) {
            throw new IllegalArgumentException("Process kill grace must be positive");
        }
        this.killGrace = killGrace;
    }

    public SupervisedProcessResult run(
            ProcessBuilder builder,
            Duration timeout,
            long outputLimitBytes
    ) {
        if (builder == null || timeout == null || timeout.isZero() || timeout.isNegative()
                || outputLimitBytes < 1) {
            throw new IllegalArgumentException("Process supervision parameters are invalid");
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException exception) {
            throw new BackupProcessException("Backup process could not be started", exception);
        }
        closeQuietly(process.getOutputStream());

        AtomicReference<BackupProcessException> failureSignal = new AtomicReference<>();
        ExecutorService readers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("backup-process-output-", 0).factory());
        CompletableFuture<BoundedProcessOutput> stdout = CompletableFuture.supplyAsync(
                () -> BoundedProcessOutput.capture(
                        process.getInputStream(), outputLimitBytes, failureSignal), readers);
        CompletableFuture<BoundedProcessOutput> stderr = CompletableFuture.supplyAsync(
                () -> BoundedProcessOutput.capture(
                        process.getErrorStream(), outputLimitBytes, failureSignal), readers);
        Map<Long, ProcessHandle> observed = new LinkedHashMap<>();
        observed.put(process.pid(), process.toHandle());
        long deadline = deadlineAfter(timeout);

        try {
            while (true) {
                observeDescendants(process, observed);
                BackupProcessException readerFailure = failureSignal.get();
                if (readerFailure != null) {
                    terminateAndJoin(process, observed, stdout, stderr);
                    throw readerFailure;
                }
                if (!process.isAlive() && stdout.isDone() && stderr.isDone()) {
                    break;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    BackupProcessTimeoutException timedOut = new BackupProcessTimeoutException();
                    failureSignal.compareAndSet(null, timedOut);
                    terminateAndJoin(process, observed, stdout, stderr);
                    throw timedOut;
                }
                long waitNanos = Math.min(remaining, POLL_INTERVAL.toNanos());
                if (process.isAlive()) {
                    process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
                } else {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                }
            }

            BoundedProcessOutput capturedStdout = completed(stdout);
            BoundedProcessOutput capturedStderr = completed(stderr);
            BackupProcessException readerFailure = failureSignal.get();
            if (readerFailure != null) {
                throw readerFailure;
            }
            return new SupervisedProcessResult(
                    process.exitValue(), capturedStdout, capturedStderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            BackupProcessException interrupted = new BackupProcessException(
                    "Backup process supervision was interrupted", exception);
            failureSignal.compareAndSet(null, interrupted);
            terminateAndJoin(process, observed, stdout, stderr);
            throw interrupted;
        } finally {
            readers.shutdownNow();
        }
    }

    private long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, timeout.toNanos());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void observeDescendants(Process process, Map<Long, ProcessHandle> observed) {
        try {
            process.descendants().forEach(handle -> observed.putIfAbsent(handle.pid(), handle));
        } catch (RuntimeException ignored) {
// 之前观察到的子进程仍可用于强制终止。
        }
    }

    private void terminateAndJoin(
            Process process,
            Map<Long, ProcessHandle> observed,
            CompletableFuture<BoundedProcessOutput> stdout,
            CompletableFuture<BoundedProcessOutput> stderr
    ) {
        observeDescendants(process, observed);
        List<ProcessHandle> descendants = observed.values().stream()
                .filter(handle -> handle.pid() != process.pid())
                .toList()
                .reversed();
        descendants.forEach(this::forceKill);
        forceKill(process.toHandle());
        awaitStopped(observed.values().stream().toList(), killGrace);
        observed.values().stream().filter(ProcessHandle::isAlive).forEach(this::forceKill);
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
        joinAfterTermination(stdout, killGrace);
        joinAfterTermination(stderr, killGrace);
        awaitStopped(observed.values().stream().toList(), killGrace);
    }

    private void forceKill(ProcessHandle handle) {
        if (handle.isAlive()) {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
// 随后执行第二轮有界终止。
            }
        }
    }

    private void awaitStopped(List<ProcessHandle> handles, Duration grace) {
        long deadline = deadlineAfter(grace);
        while (handles.stream().anyMatch(ProcessHandle::isAlive)
                && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void joinAfterTermination(
            CompletableFuture<BoundedProcessOutput> reader,
            Duration grace
    ) {
        try {
            reader.get(grace.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | TimeoutException ignored) {
            reader.cancel(true);
        }
    }

    private BoundedProcessOutput completed(
            CompletableFuture<BoundedProcessOutput> reader
    ) {
        try {
            return reader.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof BackupProcessException processException) {
                throw processException;
            }
            throw new BackupProcessException(
                    "Backup process output reader failed", exception.getCause());
        }
    }

    private void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
// 进程终止结果仍是最终依据。
        }
    }
}

package xyz.v185v.collegeassistant.ingestion.parsing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 在有界子 JVM 中运行 Tika，避免格式异常文档长期占用应用堆。 */
@Primary
@Component
public class IsolatedTikaDocumentParser implements DocumentParser {

    public static final int DEFAULT_MAX_INPUT_BYTES = 20 * 1024 * 1024;
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 24 * 1024 * 1024;

    private static final int MAX_STDERR_BYTES = 64 * 1024;
    private static final String FAILURE_MESSAGE = "Document parsing failed";

    private final ParserWorkerCommandFactory commandFactory;
    private final Duration timeout;
    private final int maxInputBytes;
    private final int maxOutputBytes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public IsolatedTikaDocumentParser(
            ParserWorkerCommandFactory commandFactory,
            @Value("${app.ingestion.parser-timeout:PT30S}") Duration timeout
    ) {
        this(commandFactory, timeout, DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public IsolatedTikaDocumentParser(
            ParserWorkerCommandFactory commandFactory,
            Duration timeout,
            int maxInputBytes,
            int maxOutputBytes
    ) {
        if (commandFactory == null || timeout == null || timeout.isZero() || timeout.isNegative()
                || maxInputBytes <= 0 || maxOutputBytes <= 0) {
            throw new IllegalArgumentException("Isolated parser limits are invalid");
        }
        this.commandFactory = commandFactory;
        this.timeout = timeout;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public ParsedDocument parse(InputStream input, String resourceName) {
        byte[] bytes = readInput(input);
        Process process = null;
        ExecutorService ioExecutor = Executors.newFixedThreadPool(3);
        try {
            process = commandFactory.processBuilder(resourceName).start();
            Process worker = process;
            Future<byte[]> stdout = ioExecutor.submit(
                    () -> readBounded(worker.getInputStream(), maxOutputBytes));
            Future<byte[]> stderr = ioExecutor.submit(
                    () -> readBounded(worker.getErrorStream(), MAX_STDERR_BYTES));
            Future<Void> stdin = ioExecutor.submit(() -> {
                try (var output = worker.getOutputStream()) {
                    output.write(bytes);
                    output.flush();
                }
                return null;
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateTree(process);
                throw failure(null);
            }
            waitFor(stdin);
            byte[] output = get(stdout);
            get(stderr);
            if (process.exitValue() != 0 || output.length == 0) {
                throw failure(null);
            }
            return objectMapper.readValue(output, ParsedDocument.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw failure(exception);
        } finally {
            if (process != null && process.isAlive()) {
                terminateTree(process);
            }
            ioExecutor.shutdownNow();
        }
    }

    private byte[] readInput(InputStream input) {
        if (input == null) {
            throw failure(null);
        }
        try {
            byte[] bytes = input.readNBytes(maxInputBytes + 1);
            if (bytes.length > maxInputBytes) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_FAILED, "Document exceeds the upload limit");
            }
            return bytes;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(exception);
        }
    }

    private byte[] readBounded(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IOException("worker output limit exceeded");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void waitFor(Future<Void> future) {
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw failure(exception);
        }
    }

    private byte[] get(Future<byte[]> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw failure(exception);
        }
    }

    private void terminateTree(Process process) {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants = new ArrayList<>(root.descendants().toList());
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        descendants.forEach(ProcessHandle::destroyForcibly);
        root.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private BusinessException failure(Throwable cause) {
        return cause == null
                ? new BusinessException(ErrorCode.INGEST_FAILED, FAILURE_MESSAGE)
                : new BusinessException(ErrorCode.INGEST_FAILED, FAILURE_MESSAGE, cause);
    }
}

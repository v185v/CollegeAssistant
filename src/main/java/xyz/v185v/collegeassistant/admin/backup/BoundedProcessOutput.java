package xyz.v185v.collegeassistant.admin.backup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 在严格字节上限下捕获的不可变进程输出。 */
public final class BoundedProcessOutput {

    private static final int BUFFER_SIZE = 8 * 1024;

    private final byte[] bytes;

    public BoundedProcessOutput(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String utf8() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static BoundedProcessOutput capture(
            InputStream source,
            long limitBytes,
            AtomicReference<BackupProcessException> failureSignal
    ) {
        Objects.requireNonNull(source, "source");
        if (limitBytes < 1) {
            throw new IllegalArgumentException("Process output limit must be positive");
        }
        int initialCapacity = (int) Math.min(limitBytes, BUFFER_SIZE);
        ByteArrayOutputStream captured = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try {
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                if (count > limitBytes - total) {
                    BackupProcessOutputLimitException overflow =
                            new BackupProcessOutputLimitException();
                    failureSignal.compareAndSet(null, overflow);
                    throw overflow;
                }
                captured.write(buffer, 0, count);
                total += count;
            }
            return new BoundedProcessOutput(captured.toByteArray());
        } catch (IOException exception) {
            BackupProcessException failure = new BackupProcessException(
                    "Backup process output could not be captured", exception);
            failureSignal.compareAndSet(null, failure);
            throw failure;
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }
}

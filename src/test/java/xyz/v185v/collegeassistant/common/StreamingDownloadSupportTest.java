package xyz.v185v.collegeassistant.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class StreamingDownloadSupportTest {

    private final StreamingDownloadSupport support = new StreamingDownloadSupport();

    @Test
    void successfulTransferClosesTheOwnedSource() throws Exception {
        TrackingInputStream input = new TrackingInputStream("backup".getBytes());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        support.stream(input).writeTo(output);

        assertThat(output.toByteArray()).isEqualTo("backup".getBytes());
        assertThat(input.closed).isTrue();
    }

    @Test
    void clientDisconnectStillClosesTheOwnedSource() {
        TrackingInputStream input = new TrackingInputStream("backup".getBytes());
        StreamingResponseBody body = support.stream(input);
        OutputStream disconnected = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> body.writeTo(disconnected))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("client disconnected");
        assertThat(input.closed).isTrue();
    }

    @Test
    void sourceReadFailureStillClosesTheOwnedSource() {
        TrackingInputStream input = new TrackingInputStream("backup".getBytes()) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("storage read failed");
            }
        };

        assertThatThrownBy(() -> support.stream(input).writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("storage read failed");
        assertThat(input.closed).isTrue();
    }

    private static class TrackingInputStream extends FilterInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}

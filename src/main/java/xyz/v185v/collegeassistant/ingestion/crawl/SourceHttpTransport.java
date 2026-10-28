package xyz.v185v.collegeassistant.ingestion.crawl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/** 数据源爬虫使用的最小流式 HTTP 传输层。 */
public interface SourceHttpTransport {

    Response get(URI uri, Duration requestTimeout) throws IOException, InterruptedException;

    default Response get(
            URI uri,
            Duration requestTimeout,
            RequestValidators validators
    ) throws IOException, InterruptedException {
        return get(uri, requestTimeout);
    }

    interface Response extends AutoCloseable {

        int statusCode();

        Optional<String> firstHeader(String name);

        InputStream body() throws IOException;

        @Override
        void close() throws IOException;
    }
}

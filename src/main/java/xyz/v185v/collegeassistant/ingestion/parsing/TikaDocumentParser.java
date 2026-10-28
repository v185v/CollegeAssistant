package xyz.v185v.collegeassistant.ingestion.parsing;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * 基于 Apache Tika 自动探测格式并抽取正文、标题和元数据的文档解析器。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    public static final int DEFAULT_MAX_EXTRACTED_CHARS = 5_000_000;

    private static final String INGEST_ERROR_MESSAGE = "Document parsing failed";
    private static final String DEFAULT_TITLE = "Untitled document";
    private static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";

    private final AutoDetectParser parser;
    private final int maxExtractedChars;

    public TikaDocumentParser() {
        this(DEFAULT_MAX_EXTRACTED_CHARS);
    }

    public TikaDocumentParser(int maxExtractedChars) {
        if (maxExtractedChars <= 0) {
            throw new IllegalArgumentException("maxExtractedChars must be positive");
        }
        this.parser = new AutoDetectParser();
        this.maxExtractedChars = maxExtractedChars;
    }

    /**
     * 解析输入文档，并限制最大抽取字符数以控制资源占用。
     *
     * @param input        调用方持有的输入流，本方法不会关闭它
     * @param resourceName 可选资源名
     * @return 标准化后的文档
     */
    @Override
    public ParsedDocument parse(InputStream input, String resourceName) {
        if (input == null) {
            throw ingestFailure(null);
        }

        Metadata metadata = new Metadata();
        if (resourceName != null && !resourceName.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, resourceName);
        }
        BodyContentHandler content = new BodyContentHandler(maxExtractedChars);

        try (TikaInputStream tikaInput = TikaInputStream.get(
                new NonClosingInputStream(input), metadata)) {
            parser.parse(tikaInput, content, metadata, new ParseContext());

            String text = content.toString().strip();
            if (text.isBlank()) {
                throw ingestFailure(null);
            }
            String title = firstNonBlank(
                    metadata.get(TikaCoreProperties.TITLE),
                    titleFromResourceName(resourceName),
                    DEFAULT_TITLE);
            String mediaType = firstNonBlank(metadata.get(Metadata.CONTENT_TYPE), DEFAULT_MEDIA_TYPE);
            return new ParsedDocument(title, text, mediaType, copyMetadata(metadata));
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | SAXException | TikaException | RuntimeException exception) {
            throw ingestFailure(exception);
        }
    }

    private Map<String, String> copyMetadata(Metadata metadata) {
        Map<String, String> copy = new LinkedHashMap<>();
        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value != null) {
                copy.put(name, value);
            }
        }
        return copy;
    }

    private String titleFromResourceName(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        String normalized = resourceName.replace('\\', '/');
        int lastSeparator = normalized.lastIndexOf('/');
        String filename = normalized.substring(lastSeparator + 1).strip();
        if (filename.isEmpty()) {
            return null;
        }
        int extensionSeparator = filename.lastIndexOf('.');
        if (extensionSeparator > 0) {
            filename = filename.substring(0, extensionSeparator);
        }
        return filename.isBlank() ? null : filename;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        throw new IllegalStateException("At least one fallback value is required");
    }

    private BusinessException ingestFailure(Throwable cause) {
        BusinessException exception = new BusinessException(ErrorCode.INGEST_FAILED, INGEST_ERROR_MESSAGE);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private static final class NonClosingInputStream extends FilterInputStream {

        private NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
            // 输入流仍由调用方持有。
        }
    }
}

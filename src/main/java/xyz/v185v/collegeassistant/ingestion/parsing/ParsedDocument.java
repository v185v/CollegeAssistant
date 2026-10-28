package xyz.v185v.collegeassistant.ingestion.parsing;

import java.util.Map;
import java.util.Objects;

/**
 * 解析器输出的统一文档表示。
 *
 * @param title     文档标题
 * @param text      抽取后的纯文本
 * @param mediaType 探测到的媒体类型
 * @param metadata  文档元数据
 */
public record ParsedDocument(
        String title,
        String text,
        String mediaType,
        Map<String, String> metadata
) {

    public ParsedDocument {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(mediaType, "mediaType");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

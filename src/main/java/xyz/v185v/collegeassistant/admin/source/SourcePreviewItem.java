package xyz.v185v.collegeassistant.admin.source;

/** 数据源连接器发现的单个对象的纯元数据预览。 */
public record SourcePreviewItem(
        String sourceUrl,
        String filename,
        String mediaType,
        long byteSize
) {
}

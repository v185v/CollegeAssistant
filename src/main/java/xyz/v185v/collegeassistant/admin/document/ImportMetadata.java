package xyz.v185v.collegeassistant.admin.document;

import java.util.List;

/** 随一次本地导入的 multipart 文件一起提供的有序元数据。 */
public record ImportMetadata(Long sourceId, List<ImportFileMetadata> items) {
    public ImportMetadata {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

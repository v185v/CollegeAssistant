package xyz.v185v.collegeassistant.ingestion.parsing;

import java.io.InputStream;

/** 将原始文件解析为统一文本和元数据的抽象。 */
public interface DocumentParser {

    /**
     * 解析文档，但不接管输入流的生命周期。
     *
     * @param input        原始文件输入流
     * @param resourceName 用于类型探测和标题兜底的资源名
     * @return 解析后的统一文档
     */
    ParsedDocument parse(InputStream input, String resourceName);
}

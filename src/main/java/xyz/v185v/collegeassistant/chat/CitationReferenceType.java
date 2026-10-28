package xyz.v185v.collegeassistant.chat;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 可被聊天答案引用的稳定领域记录类型。 */
public enum CitationReferenceType {
    EXTERNAL,
    INTERNAL,
    UGC;

/** 接受内部下载拆分前生成的引用名称。 */
    @JsonCreator
    public static CitationReferenceType fromJson(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "EXTERNAL", "DOCUMENT" -> EXTERNAL;
            case "INTERNAL" -> INTERNAL;
            case "UGC", "UGC_POST" -> UGC;
            default -> throw new IllegalArgumentException("Unknown citation reference type");
        };
    }
}

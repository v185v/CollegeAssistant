package xyz.v185v.collegeassistant.ugc;

/** UGC 向量索引流水线的失败阶段。 */
public enum UgcIndexingStage {
    LOOKUP("lookup"),
    CHUNKING("chunking"),
    EMBEDDING("embedding"),
    PREPARATION("preparation"),
    INDEX("index");

    private final String label;

    UgcIndexingStage(String label) {
        this.label = label;
    }

    /** @return 用于结构化日志和异常消息的稳定英文标识 */
    public String label() {
        return label;
    }
}

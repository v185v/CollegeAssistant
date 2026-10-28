package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

/** 按文档持久化的摄取条目状态。 */
public enum IngestRunItemStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SKIPPED,
    FAILED
}

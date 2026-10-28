package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

/** 摄取运行状态。 */
public enum IngestRunStatus {
    RUNNING,
    DEFERRED,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED
}

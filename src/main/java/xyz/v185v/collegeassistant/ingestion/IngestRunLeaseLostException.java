package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

/** 工作线程不再拥有摄取运行的尝试/令牌租约时抛出。 */
public class IngestRunLeaseLostException extends RuntimeException {

    public IngestRunLeaseLostException() {
        super("Ingestion run lease is no longer active");
    }
}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.util.Map;

/** 摄取租约完成记录锁定和重新校验后执行的操作。 */
public interface LeaseGuardedIngestOperations {

    void checkpoint(Long itemId, IngestRunItemStatus status, Long documentId,
            Map<String, Object> checkpoint);
}

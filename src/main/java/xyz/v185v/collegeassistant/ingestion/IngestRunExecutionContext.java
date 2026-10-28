package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.util.function.Function;

/** 摄取发布代码使用的精简租约感知接口。 */
public interface IngestRunExecutionContext {

    IngestRunLease lease();

    <T> T inLeaseTransaction(Function<LeaseGuardedIngestOperations, T> work);

    void heartbeat();
}

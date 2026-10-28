package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在有界工作执行器之外独立续期排队和运行中的摄取租约。 */
@Component
public class IngestRunLeaseHeartbeatScheduler {

    private final IngestRunRepository repository;
    private final Map<Long, IngestRunLease> activeLeases = new ConcurrentHashMap<>();

    public IngestRunLeaseHeartbeatScheduler(IngestRunRepository repository) {
        this.repository = repository;
    }

    public void register(IngestRunLease lease) {
        activeLeases.put(lease.runId(), lease);
    }

    public void unregister(IngestRunLease lease) {
        activeLeases.remove(lease.runId(), lease);
    }

    @Scheduled(fixedDelayString = "${app.ingestion.lease-heartbeat-interval:PT15S}")
    public void heartbeatActiveLeases() {
        activeLeases.forEach((runId, lease) -> {
            if (!repository.heartbeat(lease)) {
                activeLeases.remove(runId, lease);
            }
        });
    }

    public int activeLeaseCount() {
        return activeLeases.size();
    }
}

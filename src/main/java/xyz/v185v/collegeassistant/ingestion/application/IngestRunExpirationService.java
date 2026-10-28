package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 原子使被遗弃的摄取租约过期，并记录每次终态转换。 */
@Service
public class IngestRunExpirationService {

    private final IngestRunRepository repository;
    private final AdminAuditService auditService;

    public IngestRunExpirationService(
            IngestRunRepository repository,
            AdminAuditService auditService
    ) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public int expireStale(Duration timeout) {
        return expire(
                AdminAuditEvent.requested(
                        null, "INGESTION_EXPIRATION", "DATABASE", null, Map.of()),
                () -> repository.expireStaleRuns(timeout));
    }

    public int expireStaleForSource(Long sourceId, Duration timeout) {
        return expire(
                AdminAuditEvent.requested(
                        null, "INGESTION_EXPIRATION", "SOURCE", sourceId, Map.of()),
                () -> repository.expireStaleRunsForSource(sourceId, timeout));
    }

    private int expire(
            AdminAuditEvent failureTemplate,
            Supplier<List<IngestRunDto>> operation
    ) {
        List<IngestRunDto> expired = auditService.auditedMany(
                failureTemplate,
                operation,
                runs -> runs.stream().map(this::terminalEvent).toList());
        return expired.size();
    }

    private AdminAuditEvent terminalEvent(IngestRunDto run) {
        return AdminAuditEvent.requested(
                        null,
                        "INGESTION_FINISHED",
                        "INGEST_RUN",
                        run.id(),
                        Map.of(
                                "sourceId", run.sourceId(),
                                "status", IngestRunStatus.FAILED.name(),
                                "runErrorCode", "LEASE_EXPIRED"))
                .failed(ErrorCode.INGEST_FAILED);
    }
}

package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在同一持久化运行上使用新的尝试/令牌重新认领 HTTP 延迟的摄取记录。 */
@Component
@ConditionalOnProperty(
        name = "app.ingestion.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DeferredIngestDispatcher {

    private final IngestRunRepository repository;
    private final IngestionService ingestionService;
    private final IngestRunExpirationService expirationService;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseTimeout;
    private final Clock clock;

    @Autowired
    public DeferredIngestDispatcher(
            IngestRunRepository repository,
            IngestionService ingestionService,
            IngestRunExpirationService expirationService,
            @Value("${app.ingestion.deferred-worker-id:}") String configuredWorkerId,
            @Value("${app.ingestion.deferred-claim-batch-size:8}") int batchSize,
            @Value("${app.ingestion.scheduler-lease-timeout:PT1H}") Duration leaseTimeout
    ) {
        this(
                repository, ingestionService, expirationService, configuredWorkerId,
                batchSize, leaseTimeout, Clock.systemUTC());
    }

    public DeferredIngestDispatcher(
            IngestRunRepository repository,
            IngestionService ingestionService,
            IngestRunExpirationService expirationService,
            String configuredWorkerId,
            int batchSize,
            Duration leaseTimeout,
            Clock clock
    ) {
        if (repository == null || ingestionService == null || clock == null) {
            throw new IllegalArgumentException("Deferred ingestion dispatcher collaborators are required");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("deferred claim batch size must be between 1 and 100");
        }
        if (leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("deferred lease timeout must be positive");
        }
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.expirationService = expirationService;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "deferred-ingestion-" + UUID.randomUUID()
                : configuredWorkerId;
        this.batchSize = batchSize;
        this.leaseTimeout = leaseTimeout;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.deferred-poll:PT30S}")
    public void resumeDeferredRuns() {
        expirationService.expireStale(leaseTimeout);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        repository.claimDueDeferred(batchSize, now, workerId).forEach(ingestionService::resume);
    }
}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.application.IngestRunExpirationService;
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

/** 轮询到期数据源计划，仅提交已由数据库事务提交的认领结果。 */
@Component
@ConditionalOnProperty(
        name = "app.ingestion.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ScheduledSourceDispatcher {

    private final IngestRunRepository repository;
    private final IngestionService ingestionService;
    private final IngestRunExpirationService expirationService;
    private final SourceScheduleCalculator scheduleCalculator;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseTimeout;
    private final Clock clock;

    @Autowired
    public ScheduledSourceDispatcher(
            IngestRunRepository repository,
            IngestionService ingestionService,
            IngestRunExpirationService expirationService,
            SourceScheduleCalculator scheduleCalculator,
            @Value("${app.ingestion.scheduler-worker-id:}") String configuredWorkerId,
            @Value("${app.ingestion.scheduler-claim-batch-size:8}") int batchSize,
            @Value("${app.ingestion.scheduler-lease-timeout:PT1H}") Duration leaseTimeout
    ) {
        this(
                repository,
                ingestionService,
                expirationService,
                scheduleCalculator,
                configuredWorkerId,
                batchSize,
                leaseTimeout,
                Clock.systemUTC());
    }

    ScheduledSourceDispatcher(
            IngestRunRepository repository,
            IngestionService ingestionService,
            IngestRunExpirationService expirationService,
            SourceScheduleCalculator scheduleCalculator,
            String configuredWorkerId,
            int batchSize,
            Duration leaseTimeout,
            Clock clock
    ) {
        if (repository == null || ingestionService == null || scheduleCalculator == null || clock == null) {
            throw new IllegalArgumentException("Scheduled source dispatcher collaborators are required");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("scheduler claim batch size must be between 1 and 100");
        }
        if (leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("scheduler lease timeout must be positive");
        }
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.expirationService = expirationService;
        this.scheduleCalculator = scheduleCalculator;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "source-scheduler-" + UUID.randomUUID()
                : configuredWorkerId;
        this.batchSize = batchSize;
        this.leaseTimeout = leaseTimeout;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.scheduler-poll:PT30S}")
    public void dispatchDueSources() {
        expirationService.expireStale(leaseTimeout);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        repository.claimDueScheduled(batchSize, now, scheduleCalculator, workerId)
                .forEach(ingestionService::submitClaimed);
    }
}

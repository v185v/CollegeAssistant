package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.document.ImportStagingStore;

/** 重试窗口过期后移除被遗弃的暂存字节。 */
@Component
public class ImportStagingCleaner {

    private final ImportStagingStore store;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public ImportStagingCleaner(
            ImportStagingStore store,
            @Value("${app.ingestion.import-staging-retention:P7D}") Duration retention
    ) {
        this(store, retention, Clock.systemUTC());
    }

    public ImportStagingCleaner(ImportStagingStore store, Duration retention, Clock clock) {
        if (store == null || retention == null || retention.isZero() || retention.isNegative()
                || clock == null) {
            throw new IllegalArgumentException("Import staging cleanup configuration is invalid");
        }
        this.store = store;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.import-staging-clean-interval:PT1H}")
    public void clean() {
        store.findOlderThan(clock.instant().minus(retention))
                .forEach(object -> store.delete(object.runId(), object.itemId()));
    }
}

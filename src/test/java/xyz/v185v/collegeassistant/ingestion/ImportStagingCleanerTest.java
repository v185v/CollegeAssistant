package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.application.ImportStagingCleaner;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.document.ImportStagingStore;
import xyz.v185v.collegeassistant.admin.document.StagedImportObject;

class ImportStagingCleanerTest {

    @Test
    void deletesOnlyObjectsReturnedByTheAgeBoundedStoreQuery() {
        ImportStagingStore store = org.mockito.Mockito.mock(ImportStagingStore.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);
        Instant cutoff = Instant.parse("2026-07-24T12:00:00Z");
        StagedImportObject old = new StagedImportObject(
                9L, 4L, 12L, Instant.parse("2026-07-20T00:00:00Z"),
                "import-staging/9/4");
        when(store.findOlderThan(cutoff)).thenReturn(List.of(old));

        new ImportStagingCleaner(store, Duration.ofHours(24), clock).clean();

        verify(store).findOlderThan(cutoff);
        verify(store).delete(9L, 4L);
    }
}

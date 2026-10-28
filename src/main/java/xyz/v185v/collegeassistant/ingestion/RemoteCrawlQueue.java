package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import java.net.URI;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

/** 在首次抓取前记录规范 URL 的持久化发现扩展点。 */
@Component
public class RemoteCrawlQueue {

    private final IngestRunItemRepository itemRepository;

    public RemoteCrawlQueue(IngestRunItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public void persist(CrawlContext context, URI uri) {
        if (context == null || context.preview() || context.runId() == null) {
            return;
        }
        String canonical = UrlCanonicalizer.canonicalize(uri).toASCIIString();
        itemRepository.upsertDiscovered(context.runId(), canonical)
                .orElseThrow(IngestRunLeaseLostException::new);
    }

    public void deferUnfinished(CrawlContext context, OffsetDateTime retryAt) {
        if (context == null || context.preview() || context.runId() == null) {
            return;
        }
        itemRepository.deferUnfinished(context.runId(), retryAt);
    }
}

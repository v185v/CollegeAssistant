package xyz.v185v.collegeassistant.admin.source;

import java.util.List;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.ingestion.crawl.SourceCrawler;
import xyz.v185v.collegeassistant.ingestion.crawl.CrawlContext;

/** 基于现有受保护单 URL 爬虫的安全预览适配器。 */
@Component
public class SingleUrlSourcePreviewGateway implements SourcePreviewGateway {

    private final SourceCrawler crawler;

    public SingleUrlSourcePreviewGateway(SourceCrawler crawler) {
        this.crawler = crawler;
    }

    @Override
    public List<SourcePreviewItem> preview(SourceRecord source) {
        if (source.connectorType() == SourceConnectorType.LOCAL_UPLOAD) {
            throw new BusinessException(
                    ErrorCode.UNSUPPORTED_OPERATION, "Local upload sources do not support remote preview");
        }
        return crawler.crawl(source, CrawlContext.previewContext(), true).stream()
                .map(document -> new SourcePreviewItem(
                        document.sourceUrl(), document.filename(), document.contentType(),
                        document.rawBytes().length))
                .toList();
    }
}

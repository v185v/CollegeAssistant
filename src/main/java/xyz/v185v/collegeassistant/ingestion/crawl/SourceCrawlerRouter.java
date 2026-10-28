package xyz.v185v.collegeassistant.ingestion.crawl;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.document.SourceRecord;

/** 将每种受管连接器类型路由到对应有界实现的主爬虫。 */
@Component
@Primary
public class SourceCrawlerRouter implements SourceCrawler {

    private final HttpSourceCrawler singleUrlCrawler;
    private final SiteSourceCrawler siteCrawler;
    private final RssSourceCrawler rssCrawler;
    private final SitemapSourceCrawler sitemapCrawler;

    public SourceCrawlerRouter(
            HttpSourceCrawler singleUrlCrawler,
            SiteSourceCrawler siteCrawler,
            RssSourceCrawler rssCrawler,
            SitemapSourceCrawler sitemapCrawler
    ) {
        this.singleUrlCrawler = singleUrlCrawler;
        this.siteCrawler = siteCrawler;
        this.rssCrawler = rssCrawler;
        this.sitemapCrawler = sitemapCrawler;
    }

    public SourceCrawler route(SourceConnectorType type) {
        if (type == null) {
            throw new IllegalArgumentException("Connector type is required");
        }
        return switch (type) {
            case SINGLE_URL -> singleUrlCrawler;
            case SITE -> siteCrawler;
            case RSS -> rssCrawler;
            case SITEMAP -> sitemapCrawler;
            case LOCAL_UPLOAD -> throw new IllegalArgumentException("Local sources are uploaded");
        };
    }

    @Override
    public List<CrawledDocument> crawl(SourceRecord source, boolean force) {
        return crawl(source, CrawlContext.direct(), force);
    }

    @Override
    public List<CrawledDocument> crawl(
            SourceRecord source,
            CrawlContext context,
            boolean force
    ) {
        if (source == null) {
            throw new IllegalArgumentException("Source is required");
        }
        return route(source.connectorType()).crawl(source, context, force);
    }
}

package xyz.v185v.collegeassistant.ingestion.crawl;

import java.util.List;

import xyz.v185v.collegeassistant.document.SourceRecord;

/** 从外部数据源抓取原始文档的抽象。 */
public interface SourceCrawler {

    /**
     * 抓取指定数据源当前可用的文档。
     *
     * @param source 数据源配置
     * @param force  是否要求强制抓取；实现可据此绕过自身缓存
     * @return 抓取到的原始文档
     */
    List<CrawledDocument> crawl(SourceRecord source, boolean force);

/** 支持持久化和预览的入口；旧实现保留双参数扩展点。 */
    default List<CrawledDocument> crawl(
            SourceRecord source,
            CrawlContext context,
            boolean force
    ) {
        return crawl(source, force);
    }
}

package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import java.time.OffsetDateTime;

/**
 * 一次数据源摄取运行的状态与计数。
 *
 * @param id          运行编号
 * @param sourceId    数据源编号
 * @param status      当前状态
 * @param added       新增文档数
 * @param updated     更新文档数
 * @param skipped     跳过文档数
 * @param failed      失败文档数
 * @param errorDetail 汇总错误信息
 * @param startedAt   开始时间
 * @param finishedAt  完成时间
 */
public record IngestRunDto(
        Long id,
        Long sourceId,
        IngestRunStatus status,
        long added,
        long updated,
        long skipped,
        long failed,
        String errorDetail,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
}

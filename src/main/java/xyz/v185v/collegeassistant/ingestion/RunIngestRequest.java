package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import jakarta.validation.constraints.Size;

/**
 * 手动运行摄取任务的参数。
 *
 * @param force          是否忽略内容摘要未变化并强制更新
 * @param rebuildVectors 是否仅重建向量并尽量复用原始对象
 * @param reason         人工触发原因
 */
public record RunIngestRequest(
        Boolean force,
        Boolean rebuildVectors,
        @Size(max = 200) String reason
) {

    public RunIngestRequest {
        force = Boolean.TRUE.equals(force);
        rebuildVectors = Boolean.TRUE.equals(rebuildVectors);
    }
}

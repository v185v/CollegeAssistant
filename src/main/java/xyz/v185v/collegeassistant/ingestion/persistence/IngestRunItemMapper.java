package xyz.v185v.collegeassistant.ingestion.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface IngestRunItemMapper extends BaseMapper<IngestRunItemDO> {

    IngestRunItemDO upsertItem(
            @Param("runId") Long runId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken,
            @Param("itemKey") String itemKey,
            @Param("itemKind") String itemKind);

    IngestRunItemDO upsertDiscovered(
            @Param("runId") Long runId,
            @Param("itemKey") String itemKey);

    int deferUnfinished(
            @Param("runId") Long runId,
            @Param("nextAttemptAt") OffsetDateTime nextAttemptAt);

    int checkpointIfLeaseActive(
            @Param("runId") Long runId,
            @Param("attempt") int attempt,
            @Param("leaseToken") UUID leaseToken,
            @Param("itemId") Long itemId,
            @Param("status") String status,
            @Param("documentId") Long documentId,
            @Param("checkpointJson") String checkpointJson);
}

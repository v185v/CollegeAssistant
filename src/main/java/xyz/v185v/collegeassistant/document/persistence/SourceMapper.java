package xyz.v185v.collegeassistant.document.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface SourceMapper extends BaseMapper<SourceDO> {

    SourceDO insertReturning(@Param("source") SourceDO source);

    SourceDO updateReturning(@Param("id") Long id, @Param("source") SourceDO source);

    SourceDO lockById(@Param("id") Long id);

    List<SourceDO> lockDueScheduled(
            @Param("now") OffsetDateTime now,
            @Param("batchSize") int batchSize);

    int advanceScheduledRun(
            @Param("id") Long id,
            @Param("nextRunAt") OffsetDateTime nextRunAt,
            @Param("claimedAt") OffsetDateTime claimedAt);

    SourceDO setEnabledReturning(@Param("id") Long id, @Param("enabled") boolean enabled);

    int markDeleting(@Param("id") Long id);

    int markDeleteFailed(@Param("id") Long id);

    int resumeDeleting(@Param("id") Long id);

    int deleteDeleting(@Param("id") Long id);
}

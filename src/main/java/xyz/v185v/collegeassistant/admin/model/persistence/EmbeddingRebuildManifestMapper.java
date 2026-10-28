package xyz.v185v.collegeassistant.admin.model.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EmbeddingRebuildManifestMapper {

    void insertBatch(@Param("jobId") long jobId,
                     @Param("items") List<EmbeddingRebuildManifestItemDO> items);

    List<EmbeddingRebuildManifestItemDO> findByJobId(@Param("jobId") long jobId);

    int markCompleted(@Param("jobId") long jobId,
                      @Param("targetType") String targetType,
                      @Param("targetId") long targetId,
                      @Param("chunkCount") int chunkCount,
                      @Param("checksum") String checksum);

    long countItems(@Param("jobId") long jobId);

    long countIncomplete(@Param("jobId") long jobId);
}

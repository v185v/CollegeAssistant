package xyz.v185v.collegeassistant.document.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface EmbeddingChunkMapper extends BaseMapper<EmbeddingChunkDO> {

    List<EmbeddingChunkDO> findForReference(
            @Param("embeddingConfigId") long embeddingConfigId,
            @Param("docRefType") String docRefType,
            @Param("docRefId") Long docRefId);

    void setStrictHnswOrder();

    List<EmbeddingChunkDO> search(
            @Param("embeddingConfigId") long embeddingConfigId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK,
            @Param("sourceType") String sourceType,
            @Param("degree") String degree,
            @Param("college") String college,
            @Param("campus") String campus,
            @Param("gradeRange") String gradeRange);

    String lockReplacement(
            @Param("embeddingConfigId") long embeddingConfigId,
            @Param("docRefTypeLength") int docRefTypeLength,
            @Param("docRefType") String docRefType,
            @Param("docRefId") Long docRefId);

    List<EmbeddingChunkDO> insertBatchReturning(
            @Param("chunks") List<EmbeddingChunkDO> chunks);
}

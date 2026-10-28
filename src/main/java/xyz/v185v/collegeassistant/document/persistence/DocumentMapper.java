package xyz.v185v.collegeassistant.document.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface DocumentMapper extends BaseMapper<DocumentDO> {

    String lockIdentity(@Param("identity") String identity);

    DocumentDO insertDocument(@Param("document") DocumentDO document);

    DocumentDO updateDocument(@Param("id") Long id, @Param("document") DocumentDO document);

    long countFiltered(
            @Param("sourceId") Long sourceId,
            @Param("status") String status,
            @Param("title") String title,
            @Param("referenceType") String referenceType,
            @Param("ingestedFrom") OffsetDateTime ingestedFrom,
            @Param("ingestedTo") OffsetDateTime ingestedTo);

    List<DocumentDO> findFiltered(
            @Param("sourceId") Long sourceId,
            @Param("status") String status,
            @Param("title") String title,
            @Param("referenceType") String referenceType,
            @Param("ingestedFrom") OffsetDateTime ingestedFrom,
            @Param("ingestedTo") OffsetDateTime ingestedTo,
            @Param("size") int size,
            @Param("offset") long offset);

    DocumentDO lockForMutation(@Param("id") Long id);

    DocumentDO findAvailableForContent(@Param("id") Long id);

    DocumentDO lockActiveForReindex(@Param("id") Long id);

    List<DocumentDO> findActiveForEmbeddingRebuild();

    DocumentDO lockActiveForEmbeddingRebuild(@Param("id") Long id);
}

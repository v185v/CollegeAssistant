package xyz.v185v.collegeassistant.ugc.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface UgcMapper extends BaseMapper<UgcPostDO> {

    UgcPostRow createReturningPost(
            @Param("authorId") Long authorId,
            @Param("post") UgcPostDO post);

    long countPosts(
            @Param("authorId") Long authorId,
            @Param("status") String status,
            @Param("tagJson") String tagJson);

    List<UgcPostRow> findPosts(
            @Param("authorId") Long authorId,
            @Param("status") String status,
            @Param("tagJson") String tagJson,
            @Param("size") int size,
            @Param("offset") long offset);

    UgcPostRow findPostById(@Param("id") Long id);

    UgcPostRow removeReturningPost(@Param("id") Long id);

    UgcPostRow findApprovedById(@Param("id") Long id);

    Long ensureLike(
            @Param("postId") Long postId,
            @Param("userId") long userId);

    Long ensureReport(
            @Param("postId") Long postId,
            @Param("reporterId") long reporterId,
            @Param("reason") String reason);

    long countReports(@Param("postId") Long postId);

    List<UgcReportDO> findReports(
            @Param("postId") Long postId,
            @Param("size") int size,
            @Param("offset") long offset);

    UgcReportDO findReport(
            @Param("postId") Long postId,
            @Param("reportId") Long reportId);

    UgcReportDO resolveReportReturning(
            @Param("postId") Long postId,
            @Param("reportId") Long reportId,
            @Param("reviewerId") Long reviewerId,
            @Param("status") String status);

    String lockIfApproved(@Param("id") Long id);

    List<UgcPostRow> findApprovedForEmbeddingRebuild();

    UgcPostRow lockApprovedForEmbeddingRebuild(@Param("id") Long id);

    List<Long> findApprovedWithoutChunksAfter(
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    UgcPostRow reviewReturningPost(
            @Param("id") Long id,
            @Param("reviewerId") Long reviewerId,
            @Param("status") String status,
            @Param("rejectReason") String rejectReason);
}

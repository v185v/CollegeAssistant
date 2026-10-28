package xyz.v185v.collegeassistant.ugc;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户贡献内容及其审核、可信度状态。
 *
 * @param id              帖子编号
 * @param authorId        作者编号
 * @param authorName      作者展示名
 * @param title           标题
 * @param content         正文
 * @param tags            标签
 * @param relatedCourse   关联课程
 * @param relatedLocation 关联地点
 * @param status          审核状态
 * @param likes           点赞计数
 * @param reports         举报计数
 * @param rejectReason    拒绝原因
 * @param createdAt       创建时间
 * @param reviewedAt      审核时间
 */
public record UgcPostDto(
        Long id,
        Long authorId,
        String authorName,
        String title,
        String content,
        List<String> tags,
        String relatedCourse,
        String relatedLocation,
        UgcStatus status,
        long likes,
        long reports,
        String rejectReason,
        OffsetDateTime createdAt,
        OffsetDateTime reviewedAt
) {
}

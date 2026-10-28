package xyz.v185v.collegeassistant.profile;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户画像响应模型，也是 RAG 检索硬过滤的用户侧条件来源。
 *
 * @param userId         用户编号
 * @param major          专业
 * @param grade          入学年级
 * @param campus         校区
 * @param degree         学历层次
 * @param college        学院
 * @param lifecycleStage 校园生命周期阶段
 * @param courseRecords  历史课程记录
 * @param updatedAt      最近更新时间
 */
public record UserProfileDto(
        Long userId,
        String major,
        Integer grade,
        String campus,
        Degree degree,
        String college,
        LifecycleStage lifecycleStage,
        List<CourseRecordDto> courseRecords,
        OffsetDateTime updatedAt
) {
}

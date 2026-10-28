package xyz.v185v.collegeassistant.profile;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用户画像更新请求。
 *
 * @param major          专业
 * @param grade          入学年级
 * @param campus         校区
 * @param degree         学历层次
 * @param college        学院
 * @param lifecycleStage 校园生命周期阶段
 * @param courseRecords  历史课程记录
 */
public record UpdateProfileRequest(
        @Size(max = 64) String major,
        @Min(1000) @Max(9999) Integer grade,
        @Size(max = 64) String campus,
        Degree degree,
        @Size(max = 64) String college,
        LifecycleStage lifecycleStage,
        @Size(max = 100) List<@NotNull @Valid CourseRecordDto> courseRecords
) {
}

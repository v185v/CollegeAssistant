package xyz.v185v.collegeassistant.profile;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 用户画像中的历史课程记录。
 *
 * <p>长度上限与 Prompt 构建时的截断预算对齐；课程代码与课程名称至少填写一项，
 * 避免写入空白记录。</p>
 *
 * @param courseCode 课程代码
 * @param courseName 课程名称
 * @param term       修读学期
 */
public record CourseRecordDto(
        @Size(max = 64) String courseCode,
        @Size(max = 128) String courseName,
        @Size(max = 64) String term
) {

    /** 校验课程代码与课程名称至少一项非空白。 */
    @JsonIgnore
    @AssertTrue(message = "课程代码或课程名称至少填写一项")
    public boolean isIdentified() {
        return (courseCode != null && !courseCode.isBlank())
                || (courseName != null && !courseName.isBlank());
    }
}

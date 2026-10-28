package xyz.v185v.collegeassistant.ugc;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户贡献校园经验的创建请求。
 *
 * @param title           标题
 * @param content         正文
 * @param tags            标签
 * @param relatedCourse   关联课程
 * @param relatedLocation 关联地点
 */
public record CreateUgcPostRequest(
        @NotBlank @Size(min = 3, max = 80) String title,
        @NotBlank @Size(min = 20, max = 5000) String content,
        @Size(max = 10) List<@NotBlank @Size(max = 20) String> tags,
        @Size(max = 64) String relatedCourse,
        @Size(max = 64) String relatedLocation
) {
}

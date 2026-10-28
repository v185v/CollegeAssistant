package xyz.v185v.collegeassistant.admin.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 管理员发起的构建某个 PENDING 向量配置空间的请求。 */
public record EmbeddingRebuildRequest(
        @NotNull @Positive Long configurationId
) {
}

package xyz.v185v.collegeassistant.admin.source;

import jakarta.validation.constraints.NotNull;

/** 受管数据源的显式启用/禁用命令。 */
public record SourceEnabledRequest(@NotNull Boolean enabled) {
}

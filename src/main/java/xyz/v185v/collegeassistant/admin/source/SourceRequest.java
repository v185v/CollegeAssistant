package xyz.v185v.collegeassistant.admin.source;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 管理员创建或更新数据源时提供的可变配置。 */
public record SourceRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 32) String type,
        @NotNull SourceConnectorType connectorType,
        String baseUrl,
        Boolean enabled,
        @NotNull SourceScheduleType scheduleType,
        @Size(max = 64) String scheduleZone,
        @Size(max = 128) String cronExpression,
        Boolean sameHostOnly,
        Integer maxDepth,
        Integer maxPages,
        Integer maxConcurrency,
        List<String> includePatterns,
        List<String> excludePatterns
) {
    public SourceRequest {
        includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
        excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
    }
}

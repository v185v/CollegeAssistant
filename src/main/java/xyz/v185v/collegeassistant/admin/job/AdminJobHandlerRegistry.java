package xyz.v185v.collegeassistant.admin.job;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** 不可变且在启动时完成校验的任务类型到处理器映射。 */
@Component
public class AdminJobHandlerRegistry {

    private final Map<AdminJobType, AdminJobHandler> handlers;

    public AdminJobHandlerRegistry(List<AdminJobHandler> advertisedHandlers) {
        EnumMap<AdminJobType, AdminJobHandler> registered =
                new EnumMap<>(AdminJobType.class);
        for (AdminJobHandler handler : List.copyOf(advertisedHandlers)) {
            Objects.requireNonNull(handler, "Admin job handlers must not contain null");
            AdminJobType type = Objects.requireNonNull(
                    handler.type(), "Admin job handler type must not be null");
            if (registered.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException(
                        "Duplicate administration job handler for " + type);
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public Optional<AdminJobHandler> find(AdminJobType type) {
        return Optional.ofNullable(handlers.get(type));
    }
}

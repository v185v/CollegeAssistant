package xyz.v185v.collegeassistant.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class AdminJobHandlerRegistryTest {

    @Test
    void registersHandlersByTheirAdvertisedType() {
        AdminJobHandler handler = handler(AdminJobType.DOCUMENT_REINDEX);

        AdminJobHandlerRegistry registry = new AdminJobHandlerRegistry(List.of(handler));

        assertThat(registry.find(AdminJobType.DOCUMENT_REINDEX)).containsSame(handler);
        assertThat(registry.find(AdminJobType.DOCUMENT_DELETE)).isEmpty();
    }

    @Test
    void duplicateAdvertisedTypesFailFast() {
        assertThatThrownBy(() -> new AdminJobHandlerRegistry(List.of(
                handler(AdminJobType.SOURCE_DELETE),
                handler(AdminJobType.SOURCE_DELETE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOURCE_DELETE");
    }

    private AdminJobHandler handler(AdminJobType type) {
        return new AdminJobHandler() {
            @Override
            public AdminJobType type() {
                return type;
            }

            @Override
            public void execute(AdminJobExecutionContext context) {
// 无操作测试处理器。
            }
        };
    }
}

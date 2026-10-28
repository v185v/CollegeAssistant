package xyz.v185v.collegeassistant.admin;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import xyz.v185v.collegeassistant.admin.model.AdminModelService;
import xyz.v185v.collegeassistant.admin.model.ModelConfigurationDto;
import xyz.v185v.collegeassistant.admin.model.ModelConfigurationRequest;
import xyz.v185v.collegeassistant.admin.model.ModelTestRequest;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;

/** 管理员使用的安全模型清单、探测与版本创建接口。 */
@RestController
@RequestMapping("/api/admin/models")
public class AdminModelController {

    private final AdminModelService service;

    public AdminModelController(AdminModelService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ModelConfigurationDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(service.list(principal));
    }

    @PostMapping("/test")
    public ApiResponse<Void> test(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ModelTestRequest request
    ) {
        service.test(principal, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/configurations")
    public ApiResponse<ModelConfigurationDto> save(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ModelConfigurationRequest request
    ) {
        return ApiResponse.ok(service.save(principal, request));
    }
}

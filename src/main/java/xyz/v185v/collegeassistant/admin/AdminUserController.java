package xyz.v185v.collegeassistant.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.user.AdminUserDto;
import xyz.v185v.collegeassistant.admin.user.AdminUserService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;

/** 仅管理员可访问的用户治理接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminUserDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(service.list(principal, page, size));
    }

    @PatchMapping("/{userId}/role")
    public ApiResponse<AdminUserDto> setRole(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long userId,
            @Valid @RequestBody RoleRequest request
    ) {
        return ApiResponse.ok(service.setRole(principal, userId, request.role()));
    }

    @PatchMapping("/{userId}/enabled")
    public ApiResponse<AdminUserDto> setEnabled(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long userId,
            @Valid @RequestBody EnabledRequest request
    ) {
        return ApiResponse.ok(service.setEnabled(principal, userId, request.enabled()));
    }

    @PostMapping("/{userId}/invalidate-tokens")
    public ApiResponse<AdminUserDto> invalidateTokens(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long userId
    ) {
        return ApiResponse.ok(service.invalidateTokens(principal, userId));
    }

    public record RoleRequest(@NotNull UserRole role) {
    }

    public record EnabledRequest(@NotNull Boolean enabled) {
    }
}

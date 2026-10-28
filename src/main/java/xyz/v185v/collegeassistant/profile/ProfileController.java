package xyz.v185v.collegeassistant.profile;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;

/** 当前登录用户的画像查询与维护接口。 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 获取当前用户画像；尚未填写时返回空字段组成的默认画像。
     *
     * @param principal 当前登录用户
     * @return 用户画像
     */
    @GetMapping
    public ApiResponse<UserProfileDto> getProfile(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(profileService.getProfile(principal.userId()));
    }

    /**
     * 创建或覆盖当前用户画像。
     *
     * @param principal 当前登录用户
     * @param request   画像更新内容
     * @return 更新后的用户画像
     */
    @PutMapping
    public ApiResponse<UserProfileDto> updateProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ApiResponse.ok(profileService.updateProfile(principal.userId(), request));
    }
}

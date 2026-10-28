package xyz.v185v.collegeassistant.profile;

import java.util.List;

import org.springframework.stereotype.Service;

/** 用户画像应用服务。 */
@Service
public class ProfileService {

    private final ProfileRepository profileRepository;

    public ProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /**
     * 获取用户画像，不存在时构造空画像以保持接口返回结构稳定。
     *
     * @param userId 用户编号
     * @return 用户画像
     */
    public UserProfileDto getProfile(Long userId) {
        return profileRepository.findByUserId(userId)
                .orElseGet(() -> emptyProfile(userId));
    }

    /**
     * 保存用户画像。
     *
     * @param userId  用户编号
     * @param request 画像更新内容
     * @return 保存后的画像
     */
    public UserProfileDto updateProfile(Long userId, UpdateProfileRequest request) {
        return profileRepository.upsert(userId, request);
    }

    private UserProfileDto emptyProfile(Long userId) {
        return new UserProfileDto(userId, null, null, null, null, null, null, List.of(), null);
    }
}

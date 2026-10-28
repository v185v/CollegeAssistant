package xyz.v185v.collegeassistant.profile;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.profile.persistence.UserProfileDO;
import xyz.v185v.collegeassistant.profile.persistence.UserProfileMapper;

/**
 * 用户画像持久化仓储，负责 {@code user_profile} 表及课程 JSON 字段的转换。
 */
@Repository
public class ProfileRepository {

    private static final TypeReference<List<CourseRecordDto>> COURSE_RECORDS_TYPE = new TypeReference<>() {
    };

    private final UserProfileMapper userProfileMapper;
    private final ObjectMapper objectMapper;

    public ProfileRepository(UserProfileMapper userProfileMapper, ObjectMapper objectMapper) {
        this.userProfileMapper = userProfileMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询指定用户的画像。
     *
     * @param userId 用户编号
     * @return 已保存的画像，不存在时为空
     */
    public Optional<UserProfileDto> findByUserId(Long userId) {
        return Optional.ofNullable(userProfileMapper.selectById(userId))
                .map(this::mapProfile);
    }

    /**
     * 新增或覆盖指定用户的画像。
     *
     * @param userId  用户编号
     * @param request 画像更新内容
     * @return 持久化后的画像
     */
    public UserProfileDto upsert(Long userId, UpdateProfileRequest request) {
        UserProfileDO profile = new UserProfileDO();
        profile.setUserId(userId);
        profile.setMajor(request.major());
        profile.setGrade(request.grade());
        profile.setCampus(request.campus());
        profile.setDegree(enumName(request.degree()));
        profile.setCollege(request.college());
        profile.setLifecycleStage(enumName(request.lifecycleStage()));
        profile.setCourseRecordsJson(serializeCourseRecords(request.courseRecords()));

        return mapProfile(userProfileMapper.upsert(profile));
    }

    private UserProfileDto mapProfile(UserProfileDO profile) {
        return new UserProfileDto(
                profile.getUserId(),
                profile.getMajor(),
                profile.getGrade(),
                profile.getCampus(),
                enumValue(Degree.class, profile.getDegree()),
                profile.getCollege(),
                enumValue(LifecycleStage.class, profile.getLifecycleStage()),
                deserializeCourseRecords(profile.getCourseRecordsJson()),
                profile.getUpdatedAt());
    }

    private String serializeCourseRecords(List<CourseRecordDto> courseRecords) {
        try {
            return objectMapper.writeValueAsString(courseRecords == null ? List.of() : courseRecords);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize profile course records", ex);
        }
    }

    List<CourseRecordDto> deserializeCourseRecords(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<CourseRecordDto> courseRecords = objectMapper.readValue(json, COURSE_RECORDS_TYPE);
            if (courseRecords == null) {
                return List.of();
            }
            if (courseRecords.contains(null)) {
                throw new IllegalStateException("Profile course records must not contain null elements");
            }
            return List.copyOf(courseRecords);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize profile course records", ex);
        }
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <E extends Enum<E>> E enumValue(Class<E> enumType, String value) {
        return value == null ? null : Enum.valueOf(enumType, value);
    }
}

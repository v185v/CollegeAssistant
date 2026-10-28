package xyz.v185v.collegeassistant.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class ProfileRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ProfileRepository profileRepository;

    @Test
    void findByUserIdReturnsEmptyForUserWithoutProfile() {
        Long userId = insertUser("profile-empty");

        assertThat(profileRepository.findByUserId(userId)).isEmpty();
    }

    @Test
    void upsertPersistsAllFieldsAndCourseRecordsJsonb() {
        Long userId = insertUser("profile-full");
        UpdateProfileRequest request = new UpdateProfileRequest(
                "Computer Science",
                2024,
                "West Campus",
                Degree.UNDERGRADUATE,
                "School of Computing",
                LifecycleStage.ENROLLED,
                List.of(new CourseRecordDto("CS101", "Introduction to Computing", "2024-FALL")));

        UserProfileDto saved = profileRepository.upsert(userId, request);

        assertThat(saved.userId()).isEqualTo(userId);
        assertThat(saved.major()).isEqualTo(request.major());
        assertThat(saved.grade()).isEqualTo(request.grade());
        assertThat(saved.campus()).isEqualTo(request.campus());
        assertThat(saved.degree()).isEqualTo(request.degree());
        assertThat(saved.college()).isEqualTo(request.college());
        assertThat(saved.lifecycleStage()).isEqualTo(request.lifecycleStage());
        assertThat(saved.courseRecords()).containsExactlyElementsOf(request.courseRecords());
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(profileRepository.findByUserId(userId)).contains(saved);
    }

    @Test
    void secondUpsertUpdatesNullableFieldsCourseRecordsAndUpdatedAt() {
        Long userId = insertUser("profile-update");
        profileRepository.upsert(userId, new UpdateProfileRequest(
                "Computer Science",
                2024,
                "West Campus",
                Degree.UNDERGRADUATE,
                "School of Computing",
                LifecycleStage.ENROLLED,
                List.of(new CourseRecordDto("CS101", "Introduction to Computing", "2024-FALL"))));
        jdbcClient.sql("""
                        UPDATE user_profile
                        SET updated_at = now() - interval '1 day'
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .update();
        UserProfileDto beforeUpdate = profileRepository.findByUserId(userId).orElseThrow();
        UpdateProfileRequest update = new UpdateProfileRequest(
                null,
                null,
                null,
                null,
                "School of Engineering",
                LifecycleStage.GRADUATING,
                List.of(new CourseRecordDto("CS499", "Graduation Project", "2027-SPRING")));

        UserProfileDto saved = profileRepository.upsert(userId, update);

        assertThat(saved.major()).isNull();
        assertThat(saved.grade()).isNull();
        assertThat(saved.campus()).isNull();
        assertThat(saved.degree()).isNull();
        assertThat(saved.college()).isEqualTo("School of Engineering");
        assertThat(saved.lifecycleStage()).isEqualTo(LifecycleStage.GRADUATING);
        assertThat(saved.courseRecords()).containsExactlyElementsOf(update.courseRecords());
        assertThat(saved.updatedAt()).isAfter(beforeUpdate.updatedAt());
        assertThat(profileRepository.findByUserId(userId)).contains(saved);
    }

    private Long insertUser(String username) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, :passwordHash, :displayName, :role)
                        RETURNING id
                        """)
                .param("username", username)
                .param("passwordHash", "test-password-hash")
                .param("displayName", username)
                .param("role", "STUDENT")
                .query((rs, rowNum) -> rs.getLong("id"))
                .single();
    }
}

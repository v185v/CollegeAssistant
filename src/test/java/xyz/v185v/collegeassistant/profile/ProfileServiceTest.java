package xyz.v185v.collegeassistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ProfileRepository profileRepository;

    @InjectMocks
    private ProfileService profileService;

    @Test
    void getProfileReturnsEmptyProfileWhenRepositoryHasNoRow() {
        when(profileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        UserProfileDto profile = profileService.getProfile(USER_ID);

        assertThat(profile.userId()).isEqualTo(USER_ID);
        assertThat(profile.major()).isNull();
        assertThat(profile.grade()).isNull();
        assertThat(profile.campus()).isNull();
        assertThat(profile.degree()).isNull();
        assertThat(profile.college()).isNull();
        assertThat(profile.lifecycleStage()).isNull();
        assertThat(profile.courseRecords()).isEmpty();
        assertThat(profile.updatedAt()).isNull();
        verify(profileRepository).findByUserId(USER_ID);
    }

    @Test
    void updateProfileUpsertsAllFieldsForCurrentUser() {
        UpdateProfileRequest request = new UpdateProfileRequest(
                "Computer Science",
                2024,
                "West Campus",
                Degree.UNDERGRADUATE,
                "School of Computing",
                LifecycleStage.ENROLLED,
                List.of(new CourseRecordDto("CS101", "Introduction to Computing", "2024-FALL")));
        UserProfileDto updated = new UserProfileDto(
                USER_ID,
                request.major(),
                request.grade(),
                request.campus(),
                request.degree(),
                request.college(),
                request.lifecycleStage(),
                request.courseRecords(),
                OffsetDateTime.parse("2026-07-13T10:15:30+08:00"));
        when(profileRepository.upsert(USER_ID, request)).thenReturn(updated);

        UserProfileDto result = profileService.updateProfile(USER_ID, request);

        assertThat(result).isSameAs(updated);
        verify(profileRepository).upsert(USER_ID, request);
    }
}

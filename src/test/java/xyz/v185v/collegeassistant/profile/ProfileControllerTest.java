package xyz.v185v.collegeassistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(ProfileController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class ProfileControllerTest {

    private static final Long USER_ID = 7L;
    private static final String AUTHORIZATION = "Bearer profile-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void getReturnsEmptyProfileForAuthenticatedUser() throws Exception {
        authenticateUser();
        when(profileService.getProfile(USER_ID)).thenReturn(new UserProfileDto(
                USER_ID, null, null, null, null, null, null, List.of(), null));

        mockMvc.perform(get("/api/profile").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.major").value(nullValue()))
                .andExpect(jsonPath("$.data.grade").value(nullValue()))
                .andExpect(jsonPath("$.data.campus").value(nullValue()))
                .andExpect(jsonPath("$.data.degree").value(nullValue()))
                .andExpect(jsonPath("$.data.college").value(nullValue()))
                .andExpect(jsonPath("$.data.lifecycleStage").value(nullValue()))
                .andExpect(jsonPath("$.data.courseRecords").isEmpty())
                .andExpect(jsonPath("$.data.updatedAt").value(nullValue()));

        verify(profileService).getProfile(USER_ID);
    }

    @Test
    void putPersistsAllProfileFieldsForAuthenticatedUser() throws Exception {
        authenticateUser();
        UserProfileDto updated = updatedProfile();
        when(profileService.updateProfile(eq(USER_ID), any(UpdateProfileRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "major": "Computer Science",
                                  "grade": 2024,
                                  "campus": "West Campus",
                                  "degree": "UNDERGRADUATE",
                                  "college": "School of Computing",
                                  "lifecycleStage": "ENROLLED",
                                  "courseRecords": [
                                    {
                                      "courseCode": "CS101",
                                      "courseName": "Introduction to Computing",
                                      "term": "2024-FALL"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.major").value("Computer Science"))
                .andExpect(jsonPath("$.data.grade").value(2024))
                .andExpect(jsonPath("$.data.campus").value("West Campus"))
                .andExpect(jsonPath("$.data.degree").value("UNDERGRADUATE"))
                .andExpect(jsonPath("$.data.college").value("School of Computing"))
                .andExpect(jsonPath("$.data.lifecycleStage").value("ENROLLED"))
                .andExpect(jsonPath("$.data.courseRecords[0].courseCode").value("CS101"));

        ArgumentCaptor<UpdateProfileRequest> requestCaptor = ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(profileService).updateProfile(eq(USER_ID), requestCaptor.capture());
        UpdateProfileRequest request = requestCaptor.getValue();
        assertThat(request.major()).isEqualTo("Computer Science");
        assertThat(request.grade()).isEqualTo(2024);
        assertThat(request.campus()).isEqualTo("West Campus");
        assertThat(request.degree()).isEqualTo(Degree.UNDERGRADUATE);
        assertThat(request.college()).isEqualTo("School of Computing");
        assertThat(request.lifecycleStage()).isEqualTo(LifecycleStage.ENROLLED);
        assertThat(request.courseRecords()).containsExactly(
                new CourseRecordDto("CS101", "Introduction to Computing", "2024-FALL"));
    }

    @Test
    void getReturnsUpdatedProfile() throws Exception {
        authenticateUser();
        when(profileService.getProfile(USER_ID)).thenReturn(updatedProfile());

        mockMvc.perform(get("/api/profile").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.major").value("Computer Science"))
                .andExpect(jsonPath("$.data.grade").value(2024))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-07-13T10:15:30+08:00"));
    }

    @Test
    void putRejectsInvalidGrade() throws Exception {
        authenticateUser();

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grade":999}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(profileService, never()).updateProfile(eq(USER_ID), any(UpdateProfileRequest.class));
    }

    @Test
    void putRejectsProfileTextFieldsLongerThan64Characters() throws Exception {
        authenticateUser();
        String tooLong = "x".repeat(65);

        assertValidationError("""
                {"major":"%s"}
                """.formatted(tooLong));
        assertValidationError("""
                {"campus":"%s"}
                """.formatted(tooLong));
        assertValidationError("""
                {"college":"%s"}
                """.formatted(tooLong));

        verify(profileService, never()).updateProfile(eq(USER_ID), any(UpdateProfileRequest.class));
    }

    @Test
    void putRejectsMoreThan100CourseRecords() throws Exception {
        authenticateUser();
        String courseRecords = IntStream.range(0, 101)
                .mapToObj(index -> """
                        {"courseCode":"CS%s","courseName":"Course %s","term":"2024-FALL"}
                        """.formatted(index, index))
                .collect(Collectors.joining(",", "[", "]"));

        assertValidationError("{\"courseRecords\":" + courseRecords + "}");

        verify(profileService, never()).updateProfile(eq(USER_ID), any(UpdateProfileRequest.class));
    }

    @Test
    void putRejectsNullCourseRecord() throws Exception {
        authenticateUser();

        assertValidationError("""
                {"courseRecords":[null]}
                """);

        verify(profileService, never()).updateProfile(eq(USER_ID), any(UpdateProfileRequest.class));
    }

    @Test
    void putRejectsCourseRecordWithoutCodeAndName() throws Exception {
        authenticateUser();

        assertValidationError("""
                {"courseRecords":[{"courseCode":" ","courseName":"","term":"2024-FALL"}]}
                """);

        verify(profileService, never()).updateProfile(eq(USER_ID), any(UpdateProfileRequest.class));
    }

    @Test
    void putRejectsOverlongCourseRecordFields() throws Exception {
        authenticateUser();
        String longCode = "C".repeat(65);

        assertValidationError("{\"courseRecords\":[{\"courseCode\":\"%s\"}]}".formatted(longCode));

        verify(profileService, never()).updateProfile(eq(USER_ID), any(UpdateProfileRequest.class));
    }

    @Test
    void getWithoutTokenRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_MISSING));

        verify(profileService, never()).getProfile(any());
    }

    private void authenticateUser() {
        when(jwtService.parseToken("profile-token"))
                .thenReturn(new AuthPrincipal(USER_ID, "alice", UserRole.STUDENT.name()));
    }

    private void assertValidationError(String content) throws Exception {
        mockMvc.perform(put("/api/profile")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
    }

    private UserProfileDto updatedProfile() {
        return new UserProfileDto(
                USER_ID,
                "Computer Science",
                2024,
                "West Campus",
                Degree.UNDERGRADUATE,
                "School of Computing",
                LifecycleStage.ENROLLED,
                List.of(new CourseRecordDto("CS101", "Introduction to Computing", "2024-FALL")),
                OffsetDateTime.parse("2026-07-13T10:15:30+08:00"));
    }
}

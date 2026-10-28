package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(UgcController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class UgcControllerTest {

    private static final Long USER_ID = 7L;
    private static final String AUTHORIZATION = "Bearer ugc-token";
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(USER_ID, "alice", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UgcService ugcService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void postCreatesPendingPostForAuthenticatedUser() throws Exception {
        authenticate(PRINCIPAL);
        UgcPostDto created = postDto(11L, USER_ID, UgcStatus.PENDING);
        when(ugcService.create(eq(PRINCIPAL), any(CreateUgcPostRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/ugc")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "How I selected CS courses",
                                  "content": "This is a detailed course selection experience for new students.",
                                  "tags": ["course", "freshman"],
                                  "relatedCourse": "CS101",
                                  "relatedLocation": "West Campus"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.authorId").value(USER_ID))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.likes").value(0))
                .andExpect(jsonPath("$.data.reports").value(0));

        ArgumentCaptor<CreateUgcPostRequest> requestCaptor = ArgumentCaptor.forClass(CreateUgcPostRequest.class);
        verify(ugcService).create(eq(PRINCIPAL), requestCaptor.capture());
        assertThat(requestCaptor.getValue().tags()).containsExactly("course", "freshman");
    }

    @Test
    void mineTrueIsForwardedForCurrentUser() throws Exception {
        authenticate(PRINCIPAL);
        PageResult<UgcPostDto> page = new PageResult<>(
                List.of(postDto(11L, USER_ID, UgcStatus.PENDING)), 1, 1, 20, 1);
        when(ugcService.list(PRINCIPAL, 1, 20, true, null, "course")).thenReturn(page);

        mockMvc.perform(get("/api/ugc")
                        .header("Authorization", AUTHORIZATION)
                        .param("mine", "true")
                        .param("tag", "course"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.pages").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"));

        verify(ugcService).list(PRINCIPAL, 1, 20, true, null, "course");
    }

    @Test
    void invisibleDetailUsesBusinessNotFoundResponse() throws Exception {
        authenticate(PRINCIPAL);
        when(ugcService.get(PRINCIPAL, 99L))
                .thenThrow(new BusinessException(ErrorCode.UGC_NOT_FOUND, "UGC post not found"));

        mockMvc.perform(get("/api/ugc/99").header("Authorization", AUTHORIZATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.UGC_NOT_FOUND));
    }

    @Test
    void listRejectsPageBeforeOne() throws Exception {
        authenticate(PRINCIPAL);

        mockMvc.perform(get("/api/ugc")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).list(any(), any(Integer.class), any(Integer.class),
                any(Boolean.class), any(), any());
    }

    @Test
    void listRejectsSizeAboveOneHundred() throws Exception {
        authenticate(PRINCIPAL);

        mockMvc.perform(get("/api/ugc")
                        .header("Authorization", AUTHORIZATION)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).list(any(), any(Integer.class), any(Integer.class),
                any(Boolean.class), any(), any());
    }

    @Test
    void listRejectsUnknownStatusAsValidationFailure() throws Exception {
        authenticate(PRINCIPAL);

        mockMvc.perform(get("/api/ugc")
                        .header("Authorization", AUTHORIZATION)
                        .param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).list(any(), any(Integer.class), any(Integer.class),
                any(Boolean.class), any(), any());
    }

    @Test
    void listRejectsNonNumericPageAsValidationFailure() throws Exception {
        authenticate(PRINCIPAL);

        mockMvc.perform(get("/api/ugc")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).list(any(), any(Integer.class), any(Integer.class),
                any(Boolean.class), any(), any());
    }

    @Test
    void postRejectsShortRequiredFields() throws Exception {
        authenticate(PRINCIPAL);

        assertValidationError("""
                {"title":"no","content":"too short"}
                """);
    }

    @Test
    void postRejectsTooManyTags() throws Exception {
        authenticate(PRINCIPAL);
        String tags = IntStream.range(0, 11)
                .mapToObj(index -> "\"tag" + index + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        assertValidationError("""
                {"title":"Valid title","content":"This content is definitely at least twenty characters.","tags":%s}
                """.formatted(tags));
    }

    @Test
    void postRejectsBlankOrTooLongTagElements() throws Exception {
        authenticate(PRINCIPAL);

        assertValidationError("""
                {"title":"Valid title","content":"This content is definitely at least twenty characters.","tags":[""]}
                """);
        assertValidationError("""
                {"title":"Valid title","content":"This content is definitely at least twenty characters.","tags":["%s"]}
                """.formatted("x".repeat(21)));
    }

    @Test
    void postRejectsRelatedFieldsLongerThan64Characters() throws Exception {
        authenticate(PRINCIPAL);
        String tooLong = "x".repeat(65);

        assertValidationError("""
                {"title":"Valid title","content":"This content is definitely at least twenty characters.","relatedCourse":"%s"}
                """.formatted(tooLong));
        assertValidationError("""
                {"title":"Valid title","content":"This content is definitely at least twenty characters.","relatedLocation":"%s"}
                """.formatted(tooLong));
    }

    @Test
    void postWithoutTokenRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/ugc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Valid title","content":"This content is definitely at least twenty characters."}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_MISSING));

        verify(ugcService, never()).create(any(), any());
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("ugc-token")).thenReturn(principal);
    }

    private void assertValidationError(String content) throws Exception {
        mockMvc.perform(post("/api/ugc")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
    }

    private UgcPostDto postDto(Long id, Long authorId, UgcStatus status) {
        return new UgcPostDto(
                id,
                authorId,
                "Alice",
                "How I selected CS courses",
                "This is a detailed course selection experience for new students.",
                List.of("course", "freshman"),
                "CS101",
                "West Campus",
                status,
                0,
                0,
                null,
                OffsetDateTime.parse("2026-07-13T10:15:30+08:00"),
                null);
    }
}

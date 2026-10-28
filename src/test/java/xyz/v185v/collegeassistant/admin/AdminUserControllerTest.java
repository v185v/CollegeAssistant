package xyz.v185v.collegeassistant.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.admin.user.AdminUserDto;
import xyz.v185v.collegeassistant.admin.user.AdminUserService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminUserControllerTest {

    private static final String AUTHORIZATION = "Bearer user-governance-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void administratorCanPageUsersWithoutCredentialFields() throws Exception {
        authenticate(ADMIN);
        AdminUserDto user = user(7L, UserRole.STUDENT, true, 3);
        when(service.list(ADMIN, 2, 25))
                .thenReturn(new PageResult<>(List.of(user), 26, 2, 25, 2));

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "2")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(26))
                .andExpect(jsonPath("$.data.items[0].username").value("user-7"))
                .andExpect(jsonPath("$.data.items[0].enabled").value(true))
                .andExpect(jsonPath("$.data.items[0].tokenVersion").value(3))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());

        verify(service).list(ADMIN, 2, 25);
    }

    @Test
    void administratorCanChangeRoleEnablementAndInvalidateTokens() throws Exception {
        authenticate(ADMIN);
        when(service.setRole(ADMIN, 7L, UserRole.ADMIN))
                .thenReturn(user(7L, UserRole.ADMIN, true, 4));
        when(service.setEnabled(ADMIN, 7L, false))
                .thenReturn(user(7L, UserRole.ADMIN, false, 5));
        when(service.invalidateTokens(ADMIN, 7L))
                .thenReturn(user(7L, UserRole.ADMIN, false, 6));

        mockMvc.perform(patch("/api/admin/users/7/role")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.tokenVersion").value(4));
        mockMvc.perform(patch("/api/admin/users/7/enabled")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.tokenVersion").value(5));
        mockMvc.perform(post("/api/admin/users/7/invalidate-tokens")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenVersion").value(6));

        verify(service).setRole(ADMIN, 7L, UserRole.ADMIN);
        verify(service).setEnabled(ADMIN, 7L, false);
        verify(service).invalidateTokens(ADMIN, 7L);
    }

    @Test
    void mutationBodiesRequireValues() throws Exception {
        authenticate(ADMIN);

        mockMvc.perform(patch("/api/admin/users/7/role")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
        mockMvc.perform(patch("/api/admin/users/7/enabled")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(service, never()).setRole(any(), any(), any());
        verify(service, never()).setEnabled(any(), any(), any(Boolean.class));
    }

    @Test
    void studentCannotAccessUserGovernance() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));

        verify(service, never()).list(any(), any(Integer.class), any(Integer.class));
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("user-governance-token")).thenReturn(principal);
    }

    private AdminUserDto user(Long id, UserRole role, boolean enabled, long tokenVersion) {
        return new AdminUserDto(
                id,
                "user-" + id,
                "User " + id,
                role,
                enabled,
                tokenVersion,
                OffsetDateTime.parse("2026-07-25T00:00:00Z"));
    }
}

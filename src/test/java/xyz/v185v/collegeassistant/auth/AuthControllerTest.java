package xyz.v185v.collegeassistant.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.auth.dto.AuthTokenResponse;
import xyz.v185v.collegeassistant.auth.dto.RegisterRequest;
import xyz.v185v.collegeassistant.auth.dto.UserSummaryDto;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthRateLimiter authRateLimiter;

    @Test
    void registerReturnsToken() throws Exception {
        when(authService.register(any(RegisterRequest.class))).thenReturn(tokenResponse());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123","displayName":"Alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200))
                .andExpect(jsonPath("$.data.user.username").value("alice"));
    }

    @Test
    void registerAcceptsMissingDisplayName() throws Exception {
        when(authService.register(any(RegisterRequest.class))).thenReturn(tokenResponse());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void registerRejectsBlankUsername() throws Exception {
        assertRegisterValidationError("""
                {"username":" ","password":"password123","displayName":"Alice"}
                """);
    }

    @Test
    void registerRejectsUsernameOutsideLengthRange() throws Exception {
        assertRegisterValidationError("""
                {"username":"ab","password":"password123","displayName":"Alice"}
                """);
        assertRegisterValidationError("""
                {"username":"abcdefghijklmnopqrstuvwxyz1234567","password":"password123","displayName":"Alice"}
                """);
    }

    @Test
    void registerRejectsUsernameWithUnsupportedCharacters() throws Exception {
        assertRegisterValidationError("""
                {"username":"alice!","password":"password123","displayName":"Alice"}
                """);
    }

    @Test
    void registerRejectsPasswordOutsideLengthRange() throws Exception {
        assertRegisterValidationError("""
                {"username":"alice","password":"short","displayName":"Alice"}
                """);
        assertRegisterValidationError("""
                {"username":"alice","password":"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijk","displayName":"Alice"}
                """);
    }

    @Test
    void registerRejectsPasswordOverSeventyTwoUtf8BytesBeforeCallingService()
            throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"%s","displayName":"Alice"}
                                """.formatted("\u4e2d".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(authService);
    }

    @Test
    void registerRejectsDisplayNameOutsideLengthRangeWhenProvided() throws Exception {
        assertRegisterValidationError("""
                {"username":"alice","password":"password123","displayName":""}
                """);
        assertRegisterValidationError("""
                {"username":"alice","password":"password123","displayName":"abcdefghijklmnopqrstuvwxyz1234567"}
                """);
    }

    @Test
    void loginReturnsToken() throws Exception {
        when(authService.login(any())).thenReturn(tokenResponse());

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.user.username").value("alice"));

        verify(authRateLimiter).beforeLogin("203.0.113.10", "alice");
        verify(authRateLimiter).recordLoginSuccess("203.0.113.10", "alice");
        verify(authRateLimiter, never()).releaseLoginAttempt(any(), any());
    }

    @Test
    void currentUserReturnsAuthenticatedAdminSummary() throws Exception {
        when(jwtService.parseToken("admin-token"))
                .thenReturn(new AuthPrincipal(7L, "admin", UserRole.ADMIN.name()));
        when(authService.currentUser(7L))
                .thenReturn(new UserSummaryDto(7L, "admin", "Administrator", UserRole.ADMIN));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.displayName").value("Administrator"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        verify(authService).currentUser(7L);
    }

    @Test
    void loginRateLimitUsesRemoteAddressIgnoresForwardedHeaderAndSkipsService()
            throws Exception {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试"))
                .when(authRateLimiter).beforeLogin("203.0.113.10", "alice");

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后再试"));

        verify(authRateLimiter).beforeLogin("203.0.113.10", "alice");
        verifyNoInteractions(authService);
    }

    @Test
    void unauthorizedLoginRecordsFailureAndRethrowsTheOriginalResult() throws Exception {
        when(authService.login(any())).thenThrow(
                new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED));

        verify(authRateLimiter).recordLoginFailure("203.0.113.10", "alice");
        verify(authRateLimiter, never()).recordLoginSuccess(any(), any());
        verify(authRateLimiter, never()).releaseLoginAttempt(any(), any());
    }

    @Test
    void nonCredentialLoginFailureDoesNotConsumeAccountFailureWindow() throws Exception {
        when(authService.login(any())).thenThrow(
                new BusinessException(ErrorCode.INTERNAL_ERROR, "temporary failure"));

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR));

        verify(authRateLimiter, never()).recordLoginFailure(any(), any());
        verify(authRateLimiter).releaseLoginAttempt("203.0.113.10", "alice");
    }

    @Test
    void unexpectedLoginFailureReleasesInFlightReservation() throws Exception {
        when(authService.login(any())).thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR));

        verify(authRateLimiter, never()).recordLoginFailure(any(), any());
        verify(authRateLimiter).releaseLoginAttempt("203.0.113.10", "alice");
    }

    @Test
    void registrationRateLimitUsesRemoteAddressAndSkipsService() throws Exception {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试"))
                .when(authRateLimiter).beforeRegistration("203.0.113.10");

        mockMvc.perform(post("/api/auth/register")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123","displayName":"Alice"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED));

        verify(authRateLimiter).beforeRegistration("203.0.113.10");
        verifyNoInteractions(authService);
    }

    @Test
    void loginGetWithoutTokenRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_MISSING))
                .andExpect(jsonPath("$.message").value("Token 缺失"));
    }

    @Test
    void loginRequiresUsernameAndPassword() throws Exception {
        assertLoginValidationError("""
                {"username":" ","password":"password123"}
                """);
        assertLoginValidationError("""
                {"username":"alice","password":" "}
                """);
    }

    @Test
    void loginRejectsPasswordOverSeventyTwoUtf8BytesBeforeCallingService()
            throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"%s"}
                                """.formatted("\u4e2d".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(authService);
    }

    @Test
    void profileWithoutTokenReturnsTokenMissing() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_MISSING))
                .andExpect(jsonPath("$.message").value("Token 缺失"));
    }

    @Test
    void profileWithInvalidTokenReturnsTokenInvalid() throws Exception {
        when(jwtService.parseToken("invalid-token"))
                .thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID, "Token 无效"));

        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_INVALID))
                .andExpect(jsonPath("$.message").value("Token 无效"));
    }

    @Test
    void profileWithExpiredTokenReturnsTokenExpired() throws Exception {
        when(jwtService.parseToken("expired-token"))
                .thenThrow(new BusinessException(ErrorCode.TOKEN_EXPIRED, "Token 已过期"));

        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_EXPIRED))
                .andExpect(jsonPath("$.message").value("Token 已过期"));
    }

    @Test
    void adminEndpointRejectsStudentWithAdminRequired() throws Exception {
        when(jwtService.parseToken("student-token"))
                .thenReturn(new AuthPrincipal(1L, "alice", UserRole.STUDENT.name()));

        mockMvc.perform(get("/api/admin/example")
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED))
                .andExpect(jsonPath("$.message").value("需要管理员权限"));
    }

    private void assertRegisterValidationError(String content) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
    }

    private void assertLoginValidationError(String content) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
    }

    private AuthTokenResponse tokenResponse() {
        return new AuthTokenResponse(
                "Bearer",
                "access-token",
                7200,
                new UserSummaryDto(1L, "alice", "Alice", UserRole.STUDENT));
    }
}

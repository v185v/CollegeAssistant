package xyz.v185v.collegeassistant.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import xyz.v185v.collegeassistant.auth.dto.AuthTokenResponse;
import xyz.v185v.collegeassistant.auth.dto.LoginRequest;
import xyz.v185v.collegeassistant.auth.dto.RegisterRequest;
import xyz.v185v.collegeassistant.auth.dto.UserSummaryDto;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * 本地账号注册与登录接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimiter authRateLimiter;

    public AuthController(AuthService authService, AuthRateLimiter authRateLimiter) {
        this.authService = authService;
        this.authRateLimiter = authRateLimiter;
    }

    /**
     * 注册学生账号并立即签发访问令牌。
     *
     * @param request 注册信息
     * @return 访问令牌和用户摘要
     */
    @PostMapping("/register")
    public ApiResponse<AuthTokenResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest
    ) {
        authRateLimiter.beforeRegistration(servletRequest.getRemoteAddr());
        return ApiResponse.ok(authService.register(request));
    }

    /**
     * 校验账号凭据并签发访问令牌。
     *
     * @param request 登录凭据
     * @return 访问令牌和用户摘要
     */
    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        String remoteAddress = servletRequest.getRemoteAddr();
        authRateLimiter.beforeLogin(remoteAddress, request.username());
        try {
            AuthTokenResponse response = authService.login(request);
            authRateLimiter.recordLoginSuccess(remoteAddress, request.username());
            return ApiResponse.ok(response);
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.UNAUTHORIZED) {
                authRateLimiter.recordLoginFailure(remoteAddress, request.username());
            } else {
                authRateLimiter.releaseLoginAttempt(remoteAddress, request.username());
            }
            throw exception;
        } catch (RuntimeException exception) {
            authRateLimiter.releaseLoginAttempt(remoteAddress, request.username());
            throw exception;
        }
    }

    @GetMapping("/me")
    public ApiResponse<UserSummaryDto> currentUser(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(authService.currentUser(principal.userId()));
    }
}

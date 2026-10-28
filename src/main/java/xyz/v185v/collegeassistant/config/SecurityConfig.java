package xyz.v185v.collegeassistant.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.auth.JwtAuthenticationFilter;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * HTTP 安全策略配置。
 *
 * <p>应用采用无状态 JWT 认证：认证接口匿名可访问，管理接口要求管理员角色，
 * 其余 {@code /api/**} 接口要求登录。</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 构建应用的安全过滤器链。
     *
     * @param http         Spring Security 配置入口
     * @param jwtService   JWT 解析服务
     * @param objectMapper 错误响应序列化器
     * @return 已构建的安全过滤器链
     * @throws Exception 安全组件装配失败时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            boolean tokenMissing = authorization == null || !authorization.startsWith("Bearer ");
            writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    tokenMissing ? ErrorCode.TOKEN_MISSING : ErrorCode.UNAUTHORIZED,
                    tokenMissing ? "Token 缺失" : "请先登录",
                    objectMapper);
        };
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> {
            String requestUri = request.getRequestURI();
            boolean adminRequired = requestUri.equals("/api/admin") || requestUri.startsWith("/api/admin/");
            writeError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    adminRequired ? ErrorCode.ADMIN_REQUIRED : ErrorCode.FORBIDDEN,
                    adminRequired ? "需要管理员权限" : "无权访问",
                    objectMapper);
        };
    }

    private void writeError(HttpServletResponse response, int status, int code, String message, ObjectMapper objectMapper)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}

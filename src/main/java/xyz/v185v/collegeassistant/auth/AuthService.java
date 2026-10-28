package xyz.v185v.collegeassistant.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.auth.dto.AuthTokenResponse;
import xyz.v185v.collegeassistant.auth.dto.LoginRequest;
import xyz.v185v.collegeassistant.auth.dto.RegisterRequest;
import xyz.v185v.collegeassistant.auth.dto.UserSummaryDto;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * 本地账号认证服务，负责注册、密码校验和 JWT 签发。
 */
@Service
public class AuthService {

    static final String DUMMY_PASSWORD_HASH =
            "$2a$10$vbLP7Vr6RE7Qdojuex4w9ezM470rS8W10HiGeSGlxb0Wg7nujC0SC";

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            UserRepository userRepository,
            JwtService jwtService,
            JwtProperties jwtProperties,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 创建学生账号并返回登录态。
     *
     * @param request 已通过接口层校验的注册信息
     * @return 新账号的访问令牌信息
     * @throws BusinessException 用户名已存在时抛出
     */
    public AuthTokenResponse register(RegisterRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw usernameExists();
        }
        String passwordHash = passwordEncoder.encode(request.password());
        try {
            UserAccount user = userRepository.create(request.username(), passwordHash, request.displayName(), UserRole.STUDENT);
            return tokenResponse(user);
        } catch (DuplicateKeyException ex) {
            throw usernameExists();
        }
    }

    /**
     * 使用用户名和密码登录。
     *
     * @param request 登录凭据
     * @return 登录成功后的访问令牌信息
     * @throws BusinessException 用户不存在或密码错误时抛出
     */
    public AuthTokenResponse login(LoginRequest request) {
        UserAccount user = userRepository.findByUsername(request.username()).orElse(null);
        String passwordHash = user == null ? DUMMY_PASSWORD_HASH : user.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        if (user == null || !passwordMatches || !user.enabled()) {
            throw badCredentials();
        }
        return tokenResponse(user);
    }

    public UserSummaryDto currentUser(Long userId) {
        UserAccount user = userRepository.findById(userId)
                .filter(UserAccount::enabled)
                .orElseThrow(this::sessionExpired);
        return toSummary(user);
    }

    private AuthTokenResponse tokenResponse(UserAccount user) {
        String token = jwtService.generateToken(
                user.id(), user.username(), user.role().name(), user.tokenVersion());
        return new AuthTokenResponse("Bearer", token, jwtProperties.expiresInSeconds(), toSummary(user));
    }

    private UserSummaryDto toSummary(UserAccount user) {
        return new UserSummaryDto(user.id(), user.username(), user.displayName(), user.role());
    }

    private BusinessException usernameExists() {
        return new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
    }

    private BusinessException sessionExpired() {
        return new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "Login session expired; please sign in again");
    }

    private BusinessException badCredentials() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }
}

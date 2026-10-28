package xyz.v185v.collegeassistant.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/**
 * JWT 的签发与校验服务。
 *
 * <p>令牌携带用户编号、用户名和角色，签名密钥及有效期来自应用配置。</p>
 */
@Service
public class JwtService {

    private final JwtProperties properties;
    private final UserRepository userRepository;
    private final SecretKey key;

    public JwtService(JwtProperties properties, UserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为已认证用户生成签名访问令牌。
     *
     * @param userId   用户编号
     * @param username 用户名，同时作为 JWT subject
     * @param role     用户角色
     * @param tokenVersion 当前账号令牌版本
     * @return 紧凑格式的 JWT 字符串
     */
    public String generateToken(Long userId, String username, String role, long tokenVersion) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.expiresInSeconds());
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .claim("tokenVersion", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析访问令牌。
     *
     * @param token 不含 Bearer 前缀的 JWT
     * @return 令牌中的认证主体
     * @throws BusinessException 令牌过期、签名无效或关键声明缺失时抛出
     */
    public AuthPrincipal parseToken(String token) {
        ParsedToken parsed = parseClaims(token);
        UserAccount user = userRepository.findById(parsed.userId())
                .filter(UserAccount::enabled)
                .filter(account -> account.username().equals(parsed.username()))
                .filter(account -> account.role().name().equals(parsed.role()))
                .filter(account -> account.tokenVersion() == parsed.tokenVersion())
                .orElseThrow(this::invalidToken);
        return new AuthPrincipal(user.id(), user.username(), user.role().name());
    }

    private ParsedToken parseClaims(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Number userId = claims.get("userId", Number.class);
            String role = claims.get("role", String.class);
            String username = claims.getSubject();
            Number tokenVersion = claims.get("tokenVersion", Number.class);
            if (userId == null
                    || username == null
                    || username.isBlank()
                    || role == null
                    || role.isBlank()
                    || tokenVersion == null) {
                throw invalidToken();
            }
            return new ParsedToken(
                    userId.longValue(),
                    username,
                    role,
                    tokenVersion.longValue());
        } catch (ExpiredJwtException ex) {
            throw expiredToken();
        } catch (JwtException | IllegalArgumentException ex) {
            throw invalidToken();
        }
    }

    private BusinessException expiredToken() {
        return new BusinessException(ErrorCode.TOKEN_EXPIRED, "Token 已过期");
    }

    private BusinessException invalidToken() {
        return new BusinessException(ErrorCode.TOKEN_INVALID, "Token 无效");
    }

    private record ParsedToken(
            long userId,
            String username,
            String role,
            long tokenVersion
    ) {
    }
}

package xyz.v185v.collegeassistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class JwtServiceTest {

    private static final String SECRET = "test-secret-change-me-test-secret-change-me";

    private UserRepository userRepository;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtService = new JwtService(new JwtProperties(SECRET, 7200), userRepository);
    }

    @Test
    void generatedTokenCanBeParsedWhenPersistentStateMatches() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(0)));
        String token = jwtService.generateToken(1L, "alice", "STUDENT", 0);

        AuthPrincipal principal = jwtService.parseToken(token);

        assertThat(principal.userId()).isEqualTo(1L);
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.role()).isEqualTo("STUDENT");
    }

    @Test
    void invalidTokenThrowsBusinessExceptionWithoutDatabaseLookup() {
        assertTokenInvalid("bad-token");
        verifyNoInteractions(userRepository);
    }

    @Test
    void expiredTokenThrowsTokenExpiredBusinessException() {
        Instant now = Instant.now();
        String token = signedToken("alice", 1L, "STUDENT", 0,
                now.minusSeconds(7200), now.minusSeconds(3600));

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TOKEN_EXPIRED));
        verifyNoInteractions(userRepository);
    }

    @Test
    void tokenWithoutRequiredClaimsThrowsBusinessException() {
        Instant now = Instant.now();
        SecretKey key = signingKey();
        String token = Jwts.builder()
                .subject("alice")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(7200)))
                .signWith(key)
                .compact();

        assertTokenInvalid(token);
        verifyNoInteractions(userRepository);
    }

    @Test
    void oldTokenWithoutTokenVersionIsRejected() {
        Instant now = Instant.now();
        SecretKey key = signingKey();
        String token = Jwts.builder()
                .subject("alice")
                .claim("userId", 1L)
                .claim("role", "STUDENT")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(7200)))
                .signWith(key)
                .compact();

        assertTokenInvalid(token);
        verifyNoInteractions(userRepository);
    }

    @Test
    void nonNumericTokenVersionIsRejected() {
        Instant now = Instant.now();
        SecretKey key = signingKey();
        String token = Jwts.builder()
                .subject("alice")
                .claim("userId", 1L)
                .claim("role", "STUDENT")
                .claim("tokenVersion", "zero")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(7200)))
                .signWith(key)
                .compact();

        assertTokenInvalid(token);
        verifyNoInteractions(userRepository);
    }

    @Test
    void deletedAccountInvalidatesToken() {
        String token = jwtService.generateToken(1L, "alice", "STUDENT", 0);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertTokenInvalid(token);
    }

    @Test
    void disabledAccountInvalidatesToken() {
        String token = jwtService.generateToken(1L, "alice", "STUDENT", 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(new UserAccount(
                1L, "alice", "hash", "Alice", UserRole.STUDENT,
                OffsetDateTime.now(), false, 0)));

        assertTokenInvalid(token);
    }

    @Test
    void usernameChangeInvalidatesToken() {
        String token = jwtService.generateToken(1L, "alice", "STUDENT", 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(new UserAccount(
                1L, "alice-renamed", "hash", "Alice", UserRole.STUDENT,
                OffsetDateTime.now(), true, 0)));

        assertTokenInvalid(token);
    }

    @Test
    void roleChangeInvalidatesToken() {
        String token = jwtService.generateToken(1L, "alice", "STUDENT", 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(new UserAccount(
                1L, "alice", "hash", "Alice", UserRole.ADMIN,
                OffsetDateTime.now(), true, 0)));

        assertTokenInvalid(token);
    }

    @Test
    void tokenVersionIncrementInvalidatesToken() {
        String token = jwtService.generateToken(1L, "alice", "STUDENT", 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1)));

        assertTokenInvalid(token);
    }

    @Test
    void repositoryFailurePropagatesInsteadOfBecomingTokenInvalid() {
        String token = jwtService.generateToken(1L, "alice", "STUDENT", 0);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database unavailable");
        when(userRepository.findById(1L)).thenThrow(failure);

        assertThatThrownBy(() -> jwtService.parseToken(token)).isSameAs(failure);
    }

    private UserAccount activeUser(long tokenVersion) {
        return new UserAccount(
                1L,
                "alice",
                "hash",
                "Alice",
                UserRole.STUDENT,
                OffsetDateTime.now(),
                true,
                tokenVersion);
    }

    private String signedToken(
            String username,
            Long userId,
            String role,
            Object tokenVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .claim("tokenVersion", tokenVersion)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private void assertTokenInvalid(String token) {
        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TOKEN_INVALID));
    }
}

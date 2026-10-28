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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import tools.jackson.databind.json.JsonMapper;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-change-me-test-secret-change-me";

    private UserRepository userRepository;
    private JwtService jwtService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtService = new JwtService(new JwtProperties(SECRET, 7200), userRepository);
        filter = new JwtAuthenticationFilter(jwtService, JsonMapper.builder().build());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenDoesNotConvertDownstreamBusinessExceptionToUnauthorized() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(0)));
        String token = jwtService.generateToken(1L, "alice", UserRole.STUDENT.name(), 0);
        MockHttpServletRequest request = requestWithToken(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        BusinessException downstreamException = new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "resource not found");
        FilterChain downstream = (servletRequest, servletResponse) -> {
            throw downstreamException;
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, downstream))
                .isSameAs(downstreamException);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void tokenVersionMismatchStopsFilterChainWithTokenInvalid() throws Exception {
        String token = jwtService.generateToken(1L, "alice", UserRole.STUDENT.name(), 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain downstream = mock(FilterChain.class);

        filter.doFilter(requestWithToken(token), response, downstream);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":40103");
        verifyNoInteractions(downstream);
    }

    @Test
    void legacyTokenWithoutVersionStopsFilterChainBeforeDatabaseLookup() throws Exception {
        Instant now = Instant.now();
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("alice")
                .claim("userId", 1L)
                .claim("role", UserRole.STUDENT.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(7200)))
                .signWith(key)
                .compact();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain downstream = mock(FilterChain.class);

        filter.doFilter(requestWithToken(token), response, downstream);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":40103");
        verifyNoInteractions(userRepository, downstream);
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
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
}

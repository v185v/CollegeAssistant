package xyz.v185v.collegeassistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import xyz.v185v.collegeassistant.auth.dto.AuthTokenResponse;
import xyz.v185v.collegeassistant.auth.dto.LoginRequest;
import xyz.v185v.collegeassistant.auth.dto.UserSummaryDto;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class AuthServiceUnitTest {

    private static final String SECRET = "test-secret-change-me-test-secret-change-me";

    @Test
    void currentUserReturnsSummaryForEnabledAccount() {
        UserRepository repository = mock(UserRepository.class);
        AuthService service = serviceWith(repository);
        UserAccount admin = account(7L, "admin", "Administrator", UserRole.ADMIN, true);
        when(repository.findById(7L)).thenReturn(Optional.of(admin));

        UserSummaryDto summary = service.currentUser(7L);

        assertThat(summary).isEqualTo(
                new UserSummaryDto(7L, "admin", "Administrator", UserRole.ADMIN));
        verify(repository).findById(7L);
    }

    @Test
    void currentUserRejectsDisabledAccountAsUnauthorized() {
        UserRepository repository = mock(UserRepository.class);
        AuthService service = serviceWith(repository);
        when(repository.findById(7L)).thenReturn(Optional.of(
                account(7L, "admin", "Administrator", UserRole.ADMIN, false)));

        assertThatThrownBy(() -> service.currentUser(7L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.getMessage()).isEqualTo(
                            "Login session expired; please sign in again");
                });
    }

    @Test
    void currentUserRejectsMissingAccountAsUnauthorized() {
        UserRepository repository = mock(UserRepository.class);
        AuthService service = serviceWith(repository);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.currentUser(99L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.getMessage()).isEqualTo(
                            "Login session expired; please sign in again");
                });
    }

    @Test
    void missingUserStillPerformsDummyPasswordMatchAndReturnsUnauthorized() {
        UserRepository repository = mock(UserRepository.class);
        JwtService jwtService = mock(JwtService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(
                repository,
                jwtService,
                new JwtProperties(SECRET, 7200),
                passwordEncoder);
        when(repository.findByUsername("missing")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("password123", AuthService.DUMMY_PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("missing", "password123")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(passwordEncoder).matches("password123", AuthService.DUMMY_PASSWORD_HASH);
    }

    @Test
    void wrongPasswordForExistingUserUsesTheSameUnauthorizedResult() {
        UserRepository repository = mock(UserRepository.class);
        JwtService jwtService = mock(JwtService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(
                repository,
                jwtService,
                new JwtProperties(SECRET, 7200),
                passwordEncoder);
        UserAccount user = new UserAccount(
                1L,
                "alice",
                "$2a$10$stored-hash",
                "Alice",
                UserRole.STUDENT,
                OffsetDateTime.now());
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("wrong-password"), eq(user.passwordHash()))).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong-password")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(passwordEncoder).matches("wrong-password", user.passwordHash());
    }

    @Test
    void disabledUserStillChecksPasswordBeforeReturningUnauthorized() {
        UserRepository repository = mock(UserRepository.class);
        JwtService jwtService = mock(JwtService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(
                repository,
                jwtService,
                new JwtProperties(SECRET, 7200),
                passwordEncoder);
        UserAccount user = new UserAccount(
                1L,
                "alice",
                "$2a$10$stored-hash",
                "Alice",
                UserRole.STUDENT,
                OffsetDateTime.now(),
                false,
                3);
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", user.passwordHash())).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "password123")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(passwordEncoder).matches("password123", user.passwordHash());
        verifyNoInteractions(jwtService);
    }

    @Test
    void activeUserTokenCarriesCurrentPersistentVersion() {
        UserRepository repository = mock(UserRepository.class);
        JwtService jwtService = mock(JwtService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(
                repository,
                jwtService,
                new JwtProperties(SECRET, 7200),
                passwordEncoder);
        UserAccount user = new UserAccount(
                1L,
                "alice",
                "$2a$10$stored-hash",
                "Alice",
                UserRole.STUDENT,
                OffsetDateTime.now(),
                true,
                7);
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", user.passwordHash())).thenReturn(true);
        when(jwtService.generateToken(1L, "alice", "STUDENT", 7)).thenReturn("access-token");

        AuthTokenResponse response = service.login(new LoginRequest("alice", "password123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(jwtService).generateToken(1L, "alice", "STUDENT", 7);
    }

    private AuthService serviceWith(UserRepository repository) {
        return new AuthService(
                repository,
                mock(JwtService.class),
                new JwtProperties(SECRET, 7200),
                mock(PasswordEncoder.class));
    }

    private UserAccount account(
            Long id,
            String username,
            String displayName,
            UserRole role,
            boolean enabled
    ) {
        return new UserAccount(
                id,
                username,
                "$2a$10$stored-hash",
                displayName,
                role,
                OffsetDateTime.now(),
                enabled,
                3);
    }
}

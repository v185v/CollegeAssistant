package xyz.v185v.collegeassistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.auth.dto.AuthTokenResponse;
import xyz.v185v.collegeassistant.auth.dto.LoginRequest;
import xyz.v185v.collegeassistant.auth.dto.RegisterRequest;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class AuthServiceIT extends DatabaseTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void registerCreatesStudentAndReturnsToken() {
        AuthTokenResponse response = authService.register(new RegisterRequest("alice", "password123", "Alice"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.expiresIn()).isEqualTo(7200);
        assertThat(response.user().username()).isEqualTo("alice");
        assertThat(response.user().displayName()).isEqualTo("Alice");
        assertThat(response.user().role()).isEqualTo(UserRole.STUDENT);
    }

    @Test
    void duplicateUsernameFails() {
        authService.register(new RegisterRequest("alice", "password123", "Alice"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice", "password456", "Alice 2")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void loginRejectsBadPassword() {
        authService.register(new RegisterRequest("alice", "password123", "Alice"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong-password")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void disabledUserCannotLoginWithTheCorrectPassword() {
        authService.register(new RegisterRequest("alice", "password123", "Alice"));
        jdbcClient.sql("UPDATE users SET enabled = FALSE WHERE username = :username")
                .param("username", "alice")
                .update();

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "password123")))
                .isInstanceOf(BusinessException.class);
    }
}

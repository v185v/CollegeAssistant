package xyz.v185v.collegeassistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminBootstrapRunnerTest {

    @Test
    void promotesConfiguredUserOnlyWhenNoEnabledAdminExists() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AdminBootstrapProperties properties = new AdminBootstrapProperties(
                "operator", "deployment-secret");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                properties, userRepository, passwordEncoder);
        when(userRepository.hasEnabledAdmin()).thenReturn(false);
        when(passwordEncoder.encode("deployment-secret")).thenReturn("encoded-password");

        runner.run(new DefaultApplicationArguments());

        InOrder order = inOrder(userRepository, passwordEncoder);
        order.verify(userRepository).hasEnabledAdmin();
        order.verify(passwordEncoder).encode("deployment-secret");
        order.verify(userRepository).bootstrapAdmin("operator", "encoded-password");
    }

    @Test
    void skipsBootstrapWhenAnEnabledAdminExists() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                new AdminBootstrapProperties("operator", "deployment-secret"),
                userRepository,
                passwordEncoder);
        when(userRepository.hasEnabledAdmin()).thenReturn(true);

        runner.run(new DefaultApplicationArguments());

        verify(userRepository).hasEnabledAdmin();
        verify(userRepository, never()).bootstrapAdmin(anyString(), anyString());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void skipsBootstrapUnlessBothSettingsArePresent() throws Exception {
        assertThat(new AdminBootstrapProperties(null, null).configured()).isFalse();
        assertThat(new AdminBootstrapProperties("operator", " ").configured()).isFalse();
        assertThat(new AdminBootstrapProperties(" ", "deployment-secret").configured()).isFalse();
        assertThat(new AdminBootstrapProperties("operator", "deployment-secret").configured())
                .isTrue();

        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                new AdminBootstrapProperties("operator", " "),
                userRepository,
                passwordEncoder);

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(userRepository, passwordEncoder);
    }
}

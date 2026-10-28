package xyz.v185v.collegeassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.common.BusinessException;

class ModelHostPolicyTest {

    @Test
    void acceptsOnlyProviderDefaultOrDeploymentOwnedExactOrigins() {
        ModelHostPolicyProperties properties = new ModelHostPolicyProperties();
        properties.setModelHostAllowlist(List.of(
                "http://localhost:11434", "https://models.example.edu:8443"));
        ModelHostPolicy policy = new ModelHostPolicy(properties);

        assertThat(policy.requireAllowed("openai", "https://api.openai.com/v1"))
                .hasToString("https://api.openai.com/v1");
        assertThat(policy.requireAllowed("ollama", "http://localhost:11434"))
                .hasToString("http://localhost:11434/");
        assertThat(policy.requireAllowed(
                "openai", "https://models.example.edu:8443/openai/v1"))
                .hasToString("https://models.example.edu:8443/openai/v1");

        assertThatThrownBy(() -> policy.requireAllowed(
                "openai", "https://untrusted.example/v1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireAllowed(
                "ollama", "http://127.0.0.1:11434"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireAllowed(
                "openai", "http://api.openai.com/v1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAmbiguousTargetsAndWildcardAllowlistEntries() {
        ModelHostPolicyProperties properties = new ModelHostPolicyProperties();
        properties.setModelHostAllowlist(List.of("https://*.example.edu"));
        ModelHostPolicy policy = new ModelHostPolicy(properties);

        assertThatThrownBy(() -> policy.requireAllowed(
                "openai", "https://user@api.openai.com/v1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireAllowed(
                "openai", "https://api.openai.com/v1#fragment"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireAllowed(
                "openai", "https://api.openai.com/v1?token=x"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireAllowed(
                "openai", "https://api.openai.com/v1"))
                .isInstanceOf(BusinessException.class);
    }
}

package xyz.v185v.collegeassistant.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xyz.v185v.collegeassistant.ai.ModelRuntimeFactory;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.ai.config.ModelSecretCipher;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.AiProperties;

class AdminModelServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final AuthPrincipal ADMIN = new AuthPrincipal(3L, "admin", "ADMIN");

    @Test
    void retainingPersistedSecretDecryptsOldAadAndReencryptsForReservedNewId() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelRuntimeFactory runtime = mock(ModelRuntimeFactory.class);
        ModelSecretCipher cipher = new ModelSecretCipher(KEY);
        ModelSecretCipher.EncryptedSecret oldSecret =
                cipher.encrypt("secret-key".toCharArray(), "config:7:CHAT");
        ModelConfiguration old = configuration(7, ModelKind.CHAT, oldSecret);
        when(repository.findById(7)).thenReturn(Optional.of(old));
        when(repository.lockById(7)).thenReturn(Optional.of(old));
        when(repository.reserveId()).thenReturn(8L);
        when(repository.publishPending(any(ModelConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AdminModelService service = service(repository, cipher, runtime);

        ModelConfigurationRequest request = new ModelConfigurationRequest(
                ModelKind.CHAT, "openai", "gpt-4.1-mini",
                "https://api.openai.com/v1", 0.2, null,
                null, true, 7L);
        ModelConfigurationDto saved = service.save(ADMIN, request);

        ArgumentCaptor<ModelConfiguration> inserted =
                ArgumentCaptor.forClass(ModelConfiguration.class);
        verify(repository).publishPending(inserted.capture());
        verify(runtime).probe(any());
        ModelConfiguration replacement = inserted.getValue();
        assertThat(saved.id()).isEqualTo(8);
        assertThat(replacement.apiKeyCiphertext()).isNotEqualTo(old.apiKeyCiphertext());
        assertThat(cipher.decrypt(replacement.encryptedSecret(), "config:8:CHAT"))
                .containsExactly("secret-key".toCharArray());
    }

    @Test
    void embeddingSaveDelegatesAtomicPublicationToRepository() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelRuntimeFactory runtime = mock(ModelRuntimeFactory.class);
        ModelSecretCipher cipher = new ModelSecretCipher(KEY);
        when(repository.reserveId()).thenReturn(11L);
        when(repository.publishPending(any(ModelConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AdminModelService service = service(repository, cipher, runtime);

        service.save(ADMIN, new ModelConfigurationRequest(
                ModelKind.EMBEDDING, "openai", "text-embedding-3-small",
                "https://api.openai.com/v1", null, 1536,
                "new-secret".toCharArray(), false, null));

        verify(runtime).probe(any());
        verify(repository).publishPending(any(ModelConfiguration.class));
    }

    @Test
    void inventoryDecryptsOnlyToProduceAMaskedTailAndNeverReturnsSecretBytes() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelRuntimeFactory runtime = mock(ModelRuntimeFactory.class);
        ModelSecretCipher cipher = new ModelSecretCipher(KEY);
        ModelSecretCipher.EncryptedSecret encrypted =
                cipher.encrypt("top-secret-9876".toCharArray(), "config:7:CHAT");
        when(repository.findAll()).thenReturn(List.of(
                configuration(7, ModelKind.CHAT, encrypted)));
        AdminModelService service = service(repository, cipher, runtime);

        ModelConfigurationDto dto = service.list(ADMIN).getFirst();

        assertThat(dto.secretConfigured()).isTrue();
        assertThat(dto.maskedApiKey()).isEqualTo("********9876");
        assertThat(dto.toString()).doesNotContain(
                "top-secret-9876", Base64.getEncoder().encodeToString(encrypted.ciphertext()));
    }

    @Test
    void saveFailsClosedBeforeProbeWhenMasterKeyIsUnavailable() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelRuntimeFactory runtime = mock(ModelRuntimeFactory.class);
        AdminModelService service = service(
                repository, new ModelSecretCipher(""), runtime);

        assertThatThrownBy(() -> service.save(ADMIN, new ModelConfigurationRequest(
                ModelKind.CHAT, "ollama", "qwen2.5:7b",
                "http://localhost:11434", 0.7, null,
                null, false, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.MODEL_CONFIG_UNAVAILABLE));
        verifyNoInteractions(runtime, repository);
    }

    @Test
    void retainedSecretProbeReportsConfigurationUnavailableWhenMasterKeyIsUnavailable() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelRuntimeFactory runtime = mock(ModelRuntimeFactory.class);
        AdminModelService service = service(
                repository, new ModelSecretCipher(""), runtime);

        assertThatThrownBy(() -> service.test(ADMIN, new ModelTestRequest(
                ModelKind.CHAT, "openai", "gpt-4.1-mini",
                "https://api.openai.com/v1", 0.2, null,
                null, true, 7L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.MODEL_CONFIG_UNAVAILABLE));
        verifyNoInteractions(runtime, repository);
    }

    @Test
    void requestConstructorsWipeInboundSecretBuffersAfterDefensiveCopying() {
        char[] testSecret = "test-secret".toCharArray();
        char[] configurationSecret = "configuration-secret".toCharArray();

        new ModelTestRequest(
                ModelKind.CHAT, "openai", "gpt-4.1-mini",
                "https://api.openai.com/v1", 0.2, null,
                testSecret, false, null);
        new ModelConfigurationRequest(
                ModelKind.CHAT, "openai", "gpt-4.1-mini",
                "https://api.openai.com/v1", 0.2, null,
                configurationSecret, false, null);

        assertThat(testSecret).containsOnly('\0');
        assertThat(configurationSecret).containsOnly('\0');
    }

    private AdminModelService service(
            ModelConfigurationRepository repository,
            ModelSecretCipher cipher,
            ModelRuntimeFactory runtime
    ) {
        return new AdminModelService(
                repository, cipher, runtime, new AiProperties(), null);
    }

    private ModelConfiguration configuration(
            long id,
            ModelKind kind,
            ModelSecretCipher.EncryptedSecret secret
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                id, "config-" + id, kind, "openai", "model",
                "https://api.openai.com/v1",
                kind == ModelKind.CHAT ? 0.7 : null,
                kind == ModelKind.EMBEDDING ? 1536 : null,
                secret.ciphertext(), secret.iv(), secret.encryptionKeyId(),
                ModelConfigurationStatus.PENDING, 3L, null, null, now, now);
    }
}

package xyz.v185v.collegeassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.ai.config.ModelSecretCipher;
import xyz.v185v.collegeassistant.ai.config.ModelSecretUnavailableException;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class ModelConfigurationStartupActivatorTest {

    @Test
    void probesAndActivatesPendingChatOnlyAfterCandidateBuildSucceeds() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelSecretCipher cipher = mock(ModelSecretCipher.class);
        ModelRuntimeFactory runtimeFactory = mock(ModelRuntimeFactory.class);
        ModelRegistry registry = registry();
        ModelConfiguration pending = configuration(
                7, ModelKind.CHAT, ModelConfigurationStatus.PENDING, true);
        ModelRegistry.ChatContext candidate = new ModelRegistry.ChatContext(
                7, new ChatModel() { }, new StreamingChatModel() { });
        when(repository.findActive(ModelKind.CHAT)).thenReturn(Optional.of(
                configuration(1, ModelKind.CHAT, ModelConfigurationStatus.ACTIVE, false)));
        when(repository.findPending(ModelKind.CHAT)).thenReturn(Optional.of(pending));
        when(repository.findActive(ModelKind.EMBEDDING)).thenReturn(Optional.of(
                configuration(2, ModelKind.EMBEDDING, ModelConfigurationStatus.ACTIVE, false)));
        when(repository.findPending(ModelKind.EMBEDDING)).thenReturn(Optional.empty());
        when(cipher.isAvailable()).thenReturn(true);
        when(cipher.decrypt(any(ModelSecretCipher.EncryptedSecret.class), any(String.class)))
                .thenReturn("persisted-secret".toCharArray());
        when(runtimeFactory.buildChat(eq(7L), any(ModelRuntimeSpec.class)))
                .thenReturn(candidate);

        new ModelConfigurationStartupActivator(
                repository, cipher, runtimeFactory, registry).activate();

        verify(repository).activateChat(7);
        assertThat(registry.requireChat()).isSameAs(candidate);
        assertThat(registry.requireEmbedding().configurationId()).isEqualTo(2);
    }

    @Test
    void unreadablePersistedActiveEmbeddingNeverFallsBackAcrossVectorSpaces() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelSecretCipher cipher = mock(ModelSecretCipher.class);
        ModelRuntimeFactory runtimeFactory = mock(ModelRuntimeFactory.class);
        ModelRegistry registry = registry();
        registry.activateEmbedding(new EffectiveEmbeddingContext(2, gateway()));
        when(repository.findActive(ModelKind.CHAT)).thenReturn(Optional.of(
                configuration(1, ModelKind.CHAT, ModelConfigurationStatus.ACTIVE, false)));
        when(repository.findPending(ModelKind.CHAT)).thenReturn(Optional.empty());
        when(repository.findActive(ModelKind.EMBEDDING)).thenReturn(Optional.of(
                configuration(9, ModelKind.EMBEDDING, ModelConfigurationStatus.ACTIVE, true)));
        when(repository.findPending(ModelKind.EMBEDDING)).thenReturn(Optional.empty());
        when(cipher.isAvailable()).thenReturn(true);
        when(cipher.decrypt(any(ModelSecretCipher.EncryptedSecret.class), any(String.class)))
                .thenThrow(new ModelSecretUnavailableException("unreadable"));

        new ModelConfigurationStartupActivator(
                repository, cipher, runtimeFactory, registry).activate();

        assertThatThrownBy(registry::requireEmbedding)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.MODEL_CONFIG_UNAVAILABLE));
    }

    @Test
    void failedPendingEmbeddingSchedulesItsCandidatePartitionForCleanup() {
        ModelConfigurationRepository repository = mock(ModelConfigurationRepository.class);
        ModelSecretCipher cipher = mock(ModelSecretCipher.class);
        ModelRuntimeFactory runtimeFactory = mock(ModelRuntimeFactory.class);
        ModelRegistry registry = registry();
        ModelConfiguration pending = configuration(
                9, ModelKind.EMBEDDING, ModelConfigurationStatus.PENDING, true);
        when(repository.findActive(ModelKind.CHAT)).thenReturn(Optional.of(
                configuration(1, ModelKind.CHAT, ModelConfigurationStatus.ACTIVE, false)));
        when(repository.findPending(ModelKind.CHAT)).thenReturn(Optional.empty());
        when(repository.findActive(ModelKind.EMBEDDING)).thenReturn(Optional.of(
                configuration(2, ModelKind.EMBEDDING, ModelConfigurationStatus.ACTIVE, false)));
        when(repository.findPending(ModelKind.EMBEDDING)).thenReturn(Optional.of(pending));
        when(cipher.isAvailable()).thenReturn(true);
        when(cipher.decrypt(any(ModelSecretCipher.EncryptedSecret.class), any(String.class)))
                .thenThrow(new ModelSecretUnavailableException("unreadable"));

        new ModelConfigurationStartupActivator(
                repository, cipher, runtimeFactory, registry).activate();

        verify(repository).markEmbeddingFailedAndScheduleCleanup(
                9L, "Embedding model initialization failed");
    }

    private ModelRegistry registry() {
        return new ModelRegistry(
                new ModelRegistry.ChatContext(
                        1, new ChatModel() { }, new StreamingChatModel() { }),
                gateway());
    }

    private EmbeddingGateway gateway() {
        return new EmbeddingGateway() {
            @Override public List<Double> embed(String text) { return List.of(1.0); }
            @Override public List<List<Double>> embedAll(List<String> texts) {
                return texts.stream().map(ignored -> List.of(1.0)).toList();
            }
            @Override public int dimension() { return 1536; }
        };
    }

    private ModelConfiguration configuration(
            long id,
            ModelKind kind,
            ModelConfigurationStatus status,
            boolean secret
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                id,
                id == 1 ? "bootstrap-chat" : id == 2 ? "bootstrap-embedding" : "config-" + id,
                kind,
                id <= 2 ? null : "openai",
                id <= 2 ? null : "model",
                id <= 2 ? null : "https://api.openai.com/v1",
                kind == ModelKind.CHAT ? 0.7 : null,
                kind == ModelKind.EMBEDDING ? 1536 : null,
                secret ? new byte[16] : null,
                secret ? new byte[12] : null,
                secret ? "sha256:key" : null,
                status,
                null,
                status == ModelConfigurationStatus.ACTIVE ? now : null,
                null,
                now,
                now);
    }
}

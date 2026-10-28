package xyz.v185v.collegeassistant.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class ModelConfigurationRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private ModelConfigurationRepository repository;

    @Test
    void standardReadsRoundTripBinarySecretsNullableFieldsAndTimestamps() {
        long id = repository.reserveId();
        byte[] ciphertext = new byte[32];
        byte[] iv = new byte[12];
        for (int index = 0; index < ciphertext.length; index++) {
            ciphertext[index] = (byte) (index * 17);
        }
        iv[0] = (byte) 0xff;
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-27T08:00:00+08:00");
        repository.insert(new ModelConfiguration(
                id, "chat-binary-" + id, ModelKind.CHAT, "openai", "chat-test",
                "https://example.edu/v1", 0.75, null, ciphertext, iv, "test-key",
                ModelConfigurationStatus.PENDING, null, null, null, createdAt, createdAt));

        for (ModelConfiguration loaded : List.of(
                repository.findById(id).orElseThrow(),
                repository.findPending(ModelKind.CHAT).orElseThrow())) {
            assertThat(loaded.id()).isEqualTo(id);
            assertThat(loaded.apiKeyCiphertext()).containsExactly(ciphertext);
            assertThat(loaded.apiKeyIv()).containsExactly(iv);
            assertThat(loaded.encryptionKeyId()).isEqualTo("test-key");
            assertThat(loaded.temperature()).isEqualTo(0.75);
            assertThat(loaded.dimension()).isNull();
            assertThat(loaded.createdBy()).isNull();
            assertThat(loaded.activatedAt()).isNull();
            assertThat(loaded.failureReason()).isNull();
            assertThat(loaded.createdAt().toInstant()).isEqualTo(createdAt.toInstant());
        }
        ModelConfiguration embedding = repository.findActive(ModelKind.EMBEDDING).orElseThrow();
        assertThat(embedding.dimension()).isEqualTo(1536);
        assertThat(embedding.temperature()).isNull();
        assertThat(embedding.apiKeyCiphertext()).isNull();
        assertThat(repository.findPending(ModelKind.EMBEDDING)).isEmpty();
        assertThat(repository.findById(Long.MAX_VALUE)).isEmpty();

        repository.activateChat(id);
        assertThat(repository.findActive(ModelKind.CHAT)).hasValueSatisfying(active -> {
            assertThat(active.id()).isEqualTo(id);
            assertThat(active.apiKeyCiphertext()).containsExactly(ciphertext);
            assertThat(active.activatedAt()).isNotNull();
        });
        assertThat(repository.findPending(ModelKind.CHAT)).isEmpty();
    }
}

package xyz.v185v.collegeassistant.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class ModelSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void encryptsWithFreshIvAndBindsCiphertextToConfigurationAad() {
        ModelSecretCipher cipher = new ModelSecretCipher(KEY);

        ModelSecretCipher.EncryptedSecret first =
                cipher.encrypt("secret-key".toCharArray(), "config:7:CHAT");
        ModelSecretCipher.EncryptedSecret second =
                cipher.encrypt("secret-key".toCharArray(), "config:7:CHAT");

        assertThat(first.iv()).hasSize(12).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(first, "config:7:CHAT"))
                .containsExactly("secret-key".toCharArray());
        assertThatThrownBy(() -> cipher.decrypt(first, "config:8:CHAT"))
                .isInstanceOf(ModelSecretUnavailableException.class);
    }

    @Test
    void rejectsMissingMalformedAndMismatchedMasterKeysWithoutGuessing() {
        ModelSecretCipher unavailable = new ModelSecretCipher("");
        ModelSecretCipher malformed = new ModelSecretCipher(
                Base64.getEncoder().encodeToString(new byte[31]));
        ModelSecretCipher source = new ModelSecretCipher(KEY);
        ModelSecretCipher.EncryptedSecret encrypted =
                source.encrypt("secret-key".toCharArray(), "config:7:CHAT");
        ModelSecretCipher other = new ModelSecretCipher(Base64.getEncoder().encodeToString(
                "abcdef0123456789abcdef0123456789".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(unavailable.isAvailable()).isFalse();
        assertThat(malformed.isAvailable()).isFalse();
        assertThatThrownBy(() -> unavailable.encrypt(
                "secret-key".toCharArray(), "config:7:CHAT"))
                .isInstanceOf(ModelSecretUnavailableException.class);
        assertThatThrownBy(() -> other.decrypt(encrypted, "config:7:CHAT"))
                .isInstanceOf(ModelSecretUnavailableException.class);
    }

    @Test
    void encryptedSecretAccessorsReturnDefensiveCopies() {
        ModelSecretCipher cipher = new ModelSecretCipher(KEY);
        ModelSecretCipher.EncryptedSecret encrypted =
                cipher.encrypt("secret-key".toCharArray(), "config:7:CHAT");
        byte[] ciphertext = encrypted.ciphertext();
        byte[] iv = encrypted.iv();

        ciphertext[0] ^= 1;
        iv[0] ^= 1;

        assertThat(cipher.decrypt(encrypted, "config:7:CHAT"))
                .containsExactly("secret-key".toCharArray());
    }
}

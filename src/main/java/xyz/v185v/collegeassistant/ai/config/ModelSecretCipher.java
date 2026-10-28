package xyz.v185v.collegeassistant.ai.config;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 持久化模型 API 密钥的 AES-256-GCM 加密边界。 */
@Component
public class ModelSecretCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public ModelSecretCipher(@Value("${app.ai.config-encryption-key:}") String encodedKey) {
        byte[] decoded = decodeKey(encodedKey);
        if (decoded == null) {
            this.key = null;
            this.keyId = null;
            return;
        }
        try {
            this.key = new SecretKeySpec(decoded.clone(), "AES");
            this.keyId = keyId(decoded);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    public boolean isAvailable() {
        return key != null;
    }

    public String keyId() {
        requireAvailable();
        return keyId;
    }

    public void requireAvailable() {
        if (!isAvailable()) {
            throw unavailable(null);
        }
    }

    public EncryptedSecret encrypt(char[] secret, String aad) {
        requireAvailable();
        requireAad(aad);
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("Model API key must not be empty");
        }
        byte[] plaintext = encode(secret);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(cipher.doFinal(plaintext), iv, keyId);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw unavailable(exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public char[] decrypt(EncryptedSecret encrypted, String aad) {
        requireAvailable();
        requireAad(aad);
        if (encrypted == null || !keyId.equals(encrypted.encryptionKeyId())) {
            throw unavailable(null);
        }
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, encrypted.iv()));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(encrypted.ciphertext());
            return decode(plaintext);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw unavailable(exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private byte[] decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey.strip());
            if (decoded.length != KEY_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String keyId(byte[] rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey);
            try {
                return "sha256:" + java.util.HexFormat.of().formatHex(digest, 0, 8);
            } finally {
                Arrays.fill(digest, (byte) 0);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] encode(char[] value) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            wipe(buffer);
            return result;
        } catch (CharacterCodingException exception) {
            throw unavailable(exception);
        }
    }

    private char[] decode(byte[] value) {
        try {
            CharBuffer buffer = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value));
            char[] result = new char[buffer.remaining()];
            buffer.get(result);
            wipe(buffer);
            return result;
        } catch (CharacterCodingException exception) {
            throw unavailable(exception);
        }
    }

    private void wipe(ByteBuffer buffer) {
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
    }

    private void wipe(CharBuffer buffer) {
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
    }

    private void requireAad(String aad) {
        if (aad == null || aad.isBlank()) {
            throw new IllegalArgumentException("Model secret AAD must not be blank");
        }
    }

    private ModelSecretUnavailableException unavailable(Throwable cause) {
        String message = "Persisted model secrets are unavailable";
        return cause == null
                ? new ModelSecretUnavailableException(message)
                : new ModelSecretUnavailableException(message, cause);
    }

    public record EncryptedSecret(
            byte[] ciphertext,
            byte[] iv,
            String encryptionKeyId
    ) {
        public EncryptedSecret {
            ciphertext = ciphertext == null ? null : ciphertext.clone();
            iv = iv == null ? null : iv.clone();
            if (ciphertext == null || ciphertext.length < 16
                    || iv == null || iv.length != IV_BYTES
                    || encryptionKeyId == null || encryptionKeyId.isBlank()) {
                throw new IllegalArgumentException("Encrypted model secret is incomplete");
            }
            encryptionKeyId = encryptionKeyId.strip();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] iv() {
            return iv.clone();
        }
    }
}

package xyz.v185v.collegeassistant.ai.config;

/** 持久化模型密钥无法安全加密或解密时抛出。 */
public class ModelSecretUnavailableException extends RuntimeException {

    public ModelSecretUnavailableException(String message) {
        super(message);
    }

    public ModelSecretUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

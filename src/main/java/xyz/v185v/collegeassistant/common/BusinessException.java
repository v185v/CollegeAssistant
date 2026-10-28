package xyz.v185v.collegeassistant.common;

/**
 * 业务异常。携带业务状态码，由全局异常处理器转换为 {@link ApiResponse}。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR, message);
    }

    /** @return 可供 API 响应使用的业务错误码 */
    public int getCode() {
        return code;
    }
}

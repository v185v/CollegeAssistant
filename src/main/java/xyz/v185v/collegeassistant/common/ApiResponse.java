package xyz.v185v.collegeassistant.common;

/**
 * 统一 API 响应包装。
 *
 * @param code    业务状态码，0 表示成功
 * @param message 提示信息
 * @param data    业务数据
 */
public record ApiResponse<T>(int code, String message, T data) {

    /**
     * 创建携带业务数据的成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /** 创建不携带业务数据的成功响应。 */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    /**
     * 创建业务失败响应。
     *
     * @param code    业务错误码
     * @param message 面向客户端的错误信息
     * @param <T>     响应数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}

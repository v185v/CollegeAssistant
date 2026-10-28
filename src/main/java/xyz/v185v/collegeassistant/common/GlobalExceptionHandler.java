package xyz.v185v.collegeassistant.common;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * REST 接口全局异常转换器。
 *
 * <p>将业务异常、参数校验异常和未预期异常统一映射为 {@link ApiResponse}，
 * 避免向客户端泄露内部实现细节。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 将可预期的业务异常转换为业务错误响应。
     *
     * <p>业务码按「HTTP 状态 × 100 + 序号」编制（见 {@link ErrorCode}），
     * HTTP 状态直接取业务码的前三位；响应体业务码保持不变。</p>
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(
            BusinessException exception
    ) {
        return jsonError(
                statusOf(exception.getCode()),
                exception.getCode(),
                exception.getMessage());
    }

    /** 汇总请求体字段校验错误并返回客户端。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        String message = detail.isBlank()
                ? "参数校验失败" : "参数校验失败: " + detail;
        return jsonError(
                HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(
            HandlerMethodValidationException exception
    ) {
        return jsonError(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "Parameter validation failed");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        return jsonError(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "Parameter validation failed");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        return jsonError(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "Parameter type is invalid");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException exception
    ) {
        return jsonError(
                HttpStatus.BAD_REQUEST,
                ErrorCode.REQUEST_BODY_INVALID,
                "请求体格式错误");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception
    ) {
        return jsonError(
                HttpStatus.FORBIDDEN,
                ErrorCode.FORBIDDEN,
                "无权访问");
    }

    /** 将未分类异常收敛为通用服务端错误，并在服务端保留根因日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(
            HttpServletRequest request,
            Exception exception
    ) {
        LOGGER.error(
                "event=api.unexpected_error method={} path={}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return jsonError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "服务器内部错误");
    }

    private static HttpStatus statusOf(int code) {
        HttpStatus resolved = HttpStatus.resolve(code / 100);
        if (resolved == null
                || (!resolved.is4xxClientError() && !resolved.is5xxServerError())) {
            return HttpStatus.BAD_REQUEST;
        }
        return resolved;
    }

    private ResponseEntity<ApiResponse<Void>> jsonError(
            HttpStatus status,
            int code,
            String message
    ) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(code, message));
    }
}

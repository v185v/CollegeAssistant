package xyz.v185v.collegeassistant.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest
    @CsvSource({
            "40001, 400",
            "40102, 401",
            "40300, 403",
            "40400, 404",
            "40901, 409",
            "42900, 429",
            "50011, 500",
            "50302, 503",
    })
    void businessExceptionStatusFollowsCodeFamily(int code, int expectedStatus) {
        BusinessException ex = new BusinessException(code, "预期业务失败");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).isEqualTo("预期业务失败");
    }

    @Test
    void businessExceptionOutsideHttpErrorFamiliesFallsBackToBadRequest() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException(12345, "未编制的业务码"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(12345);
    }

    @Test
    void rateLimitedBusinessExceptionUsesHttp429() {
        BusinessException ex = new BusinessException(
                ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(response.getBody().message()).isEqualTo("请求过于频繁，请稍后再试");
    }

    @Test
    void genericExceptionReturnsInternalErrorCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile");

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleGeneric(request, new RuntimeException("boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(50000);
        assertThat(response.getBody().message()).isEqualTo("服务器内部错误");
    }

    @Test
    void validationExceptionReturnsValidationErrorCode() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new SampleRequest(""), "sampleRequest");
        bindingResult.rejectValue("name", "NotBlank", "不能为空");
        Method method = SampleController.class.getDeclaredMethod("handle", SampleRequest.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(40001);
        assertThat(response.getBody().message()).contains("参数校验失败").contains("name");
    }

    @Test
    void unreadableRequestBodyReturnsRequestBodyInvalidCode() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json",
                mock(HttpInputMessage.class));

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableBody(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(40002);
        assertThat(response.getBody().message()).isEqualTo("请求体格式错误");
    }

    @Test
    void accessDeniedReturnsForbiddenCode() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(40300);
        assertThat(response.getBody().message()).isEqualTo("无权访问");
    }

    private record SampleRequest(String name) {
    }

    private static final class SampleController {
        @SuppressWarnings("unused")
        void handle(SampleRequest request) {
        }
    }
}

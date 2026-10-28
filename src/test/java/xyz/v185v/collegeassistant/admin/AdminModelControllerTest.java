package xyz.v185v.collegeassistant.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.admin.model.AdminModelService;
import xyz.v185v.collegeassistant.admin.model.ModelConfigurationDto;
import xyz.v185v.collegeassistant.admin.model.ModelConfigurationRequest;
import xyz.v185v.collegeassistant.admin.model.ModelTestRequest;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AdminModelController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminModelControllerTest {

    private static final String AUTHORIZATION = "Bearer model-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AdminModelService service;
    @MockitoBean private JwtService jwtService;

    @Test
    void administratorCanListProbeAndSaveWithoutSecretMaterialInResponses() throws Exception {
        authenticate(ADMIN);
        ModelConfigurationDto dto = dto();
        when(service.list(ADMIN)).thenReturn(List.of(dto));
        when(service.save(any(), any(ModelConfigurationRequest.class))).thenReturn(dto);

        mockMvc.perform(get("/api/admin/models").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].maskedApiKey").value("********9876"))
                .andExpect(jsonPath("$.data[0].apiKeyCiphertext").doesNotExist())
                .andExpect(jsonPath("$.data[0].apiKeyIv").doesNotExist());
        mockMvc.perform(post("/api/admin/models/test")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("""
                                {"kind":"CHAT","provider":"openai","model":"gpt-4.1-mini",
                                 "baseUrl":"https://api.openai.com/v1","temperature":0.2,
                                 "apiKey":"secret-value"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/models/configurations")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("""
                                {"kind":"CHAT","provider":"openai","model":"gpt-4.1-mini",
                                 "baseUrl":"https://api.openai.com/v1","temperature":0.2,
                                 "apiKey":"secret-value"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(service).test(any(), any(ModelTestRequest.class));
        verify(service).save(any(), any(ModelConfigurationRequest.class));
    }

    @Test
    void studentIsRejectedBeforeModelSecretsReachTheService() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/models").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(post("/api/admin/models/test")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any());
        verify(service, never()).test(any(), any());
    }

    private void authenticate(AuthPrincipal actor) {
        when(jwtService.parseToken("model-token")).thenReturn(actor);
    }

    private ModelConfigurationDto dto() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new ModelConfigurationDto(
                7, "chat-7", ModelKind.CHAT, "openai", "gpt-4.1-mini",
                "https://api.openai.com/v1", 0.2, null,
                ModelConfigurationStatus.PENDING, false, true,
                "********9876", true, true, null, null, now, now);
    }
}

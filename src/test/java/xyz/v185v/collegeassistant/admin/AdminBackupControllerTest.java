package xyz.v185v.collegeassistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import xyz.v185v.collegeassistant.admin.backup.BackupDownload;
import xyz.v185v.collegeassistant.admin.backup.DatabaseBackupDto;
import xyz.v185v.collegeassistant.admin.backup.DatabaseBackupService;
import xyz.v185v.collegeassistant.admin.backup.DatabaseBackupStatus;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.common.StreamingDownloadSupport;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AdminBackupController.class)
@Import({SecurityConfig.class, StreamingDownloadSupport.class})
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminBackupControllerTest {

    private static final String AUTHORIZATION = "Bearer backup-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StreamingDownloadSupport streaming;

    @MockitoBean
    private DatabaseBackupService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void administratorCanListCreateValidateAndDeleteBackups() throws Exception {
        authenticate(ADMIN);
        DatabaseBackupDto backup = backup(DatabaseBackupStatus.SUCCEEDED);
        when(service.list(ADMIN, 1, 20))
                .thenReturn(new PageResult<>(List.of(backup), 1, 1, 20, 1));
        when(service.create(ADMIN, 14)).thenReturn(backup(DatabaseBackupStatus.QUEUED));
        when(service.validate(ADMIN, 71L)).thenReturn(backup(DatabaseBackupStatus.VALIDATED));

        mockMvc.perform(get("/api/admin/backups").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(71))
                .andExpect(jsonPath("$.data.items[0].storageKey").doesNotExist());
        mockMvc.perform(post("/api/admin/backups")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("{\"retentionDays\":14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        mockMvc.perform(post("/api/admin/backups/71/validate")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALIDATED"));
        mockMvc.perform(delete("/api/admin/backups/71")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk());

        verify(service).delete(ADMIN, 71L);
    }

    @Test
    void downloadStreamsRawBytesWithSafeHeadersAndClosesTheInput() throws Exception {
        authenticate(ADMIN);
        TrackingInputStream input = new TrackingInputStream("archive".getBytes());
        when(service.openDownload(ADMIN, 71L))
                .thenReturn(new BackupDownload(input, "college-backup-71.dump", 7));

        MvcResult started = mockMvc.perform(get("/api/admin/backups/71/download")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().bytes("archive".getBytes()))
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));
        assertThat(input.closed).isTrue();
    }

    @Test
    void downloadClosesTheInputWhenTheResponseOutputFails() throws Exception {
        TrackingInputStream input = new TrackingInputStream("archive".getBytes());
        when(service.openDownload(ADMIN, 71L))
                .thenReturn(new BackupDownload(input, "college-backup-71.dump", 7));
        AdminBackupController controller = new AdminBackupController(service, streaming);
        ResponseEntity<StreamingResponseBody> response = controller.download(ADMIN, 71L);
        OutputStream failedOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> response.getBody().writeTo(failedOutput))
                .isInstanceOf(IOException.class);
        assertThat(input.closed).isTrue();
    }

    @Test
    void studentIsRejectedBeforeAnyBackupOperationRuns() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/backups").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(post("/api/admin/backups").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/backups/71/download")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any(), any(Integer.class), any(Integer.class));
        verify(service, never()).openDownload(any(), any());
    }

    private void authenticate(AuthPrincipal actor) {
        when(jwtService.parseToken("backup-token")).thenReturn(actor);
    }

    private DatabaseBackupDto backup(DatabaseBackupStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new DatabaseBackupDto(
                71L, 81L, status, "CUSTOM", "a".repeat(64), 7L, 1L,
                now, now, 14, now.plusDays(14), null, now, now, true);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}

package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.config.SecurityConfig;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.rag.ChatEventSink;
import xyz.v185v.collegeassistant.rag.ChatStreamControl;
import xyz.v185v.collegeassistant.rag.IntentDto;
import xyz.v185v.collegeassistant.rag.IntentType;
import xyz.v185v.collegeassistant.rag.RagOrchestrator;

@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class ChatControllerTest {

    private static final Long USER_ID = 7L;
    private static final String AUTHORIZATION = "Bearer chat-token";
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(
            USER_ID, "alice", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private RagOrchestrator ragOrchestrator;

    @MockitoBean
    private ChatProperties chatProperties;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void streamEmitsTypedEventsInProtocolOrder() throws Exception {
        authenticateUser();
        when(chatProperties.streamTimeout()).thenReturn(Duration.ofSeconds(30));
        RecordingControl control = new RecordingControl();
        SourceCitationDto source = new SourceCitationDto(
                301L,
                SourceType.OFFICIAL,
                "本科生学籍管理办法",
                "https://example.edu/rule.html",
                "教务处",
                OffsetDateTime.parse("2026-06-01T09:00:00+08:00"),
                0.87,
                "绩点计算方式");
        IntentDto intent = new IntentDto(IntentType.OFFICIAL, 0.91);
        ChatDoneDto done = new ChatDoneDto(
                12L,
                52L,
                AnswerStatus.ANSWERED,
                "根据官方规定回答。",
                List.of(source));
        when(ragOrchestrator.start(
                any(AuthPrincipal.class),
                any(ChatStreamRequest.class),
                any(ChatEventSink.class)))
                .thenAnswer(invocation -> {
                    ChatEventSink sink = invocation.getArgument(2);
                    sink.meta(new ChatStreamMetaDto(12L, 52L, intent));
                    sink.delta(new ChatDeltaDto("根据官方规定"));
                    sink.source(source);
                    sink.done(done);
                    sink.complete();
                    return control;
                });

        MvcResult initial = mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"绩点怎么算？"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn();

        String stream = completed.getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream.indexOf("event:meta"))
                .isLessThan(stream.indexOf("event:delta"));
        assertThat(stream.indexOf("event:delta"))
                .isLessThan(stream.indexOf("event:source"));
        assertThat(stream.indexOf("event:source"))
                .isLessThan(stream.indexOf("event:done"));
        assertThat(stream)
                .contains("https://example.edu/rule.html")
                .contains("根据官方规定")
                .doesNotContain("fileObjectKey");
        assertThat(control.cancelled).isTrue();

        ArgumentCaptor<ChatStreamRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatStreamRequest.class);
        verify(ragOrchestrator).start(
                org.mockito.ArgumentMatchers.eq(PRINCIPAL),
                requestCaptor.capture(),
                any(ChatEventSink.class));
        assertThat(requestCaptor.getValue()).isEqualTo(
                new ChatStreamRequest(null, "绩点怎么算？", true, 8));
    }

    @Test
    void missingQuestionReturnsHttp400ValidationResponse() throws Exception {
        authenticateUser();

        mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.VALIDATION_FAILED));

        verify(ragOrchestrator, never()).start(any(), any(), any());
    }

    @Test
    void ownerNotFoundDuringStartKeepsBusinessErrorCode() throws Exception {
        authenticateUser();
        when(chatProperties.streamTimeout()).thenReturn(Duration.ofSeconds(30));
        when(ragOrchestrator.start(any(), any(), any()))
                .thenThrow(new BusinessException(
                        ErrorCode.CHAT_SESSION_NOT_FOUND,
                        "Chat session not found"));

        mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":99,"question":"问题"}
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    @Test
    void streamWithoutTokenReturnsTokenMissing() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"问题"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_MISSING));

        verify(ragOrchestrator, never()).start(any(), any(), any());
    }

    @Test
    void rejectedBearerPrincipalReturnsUnauthorizedBusinessCode()
            throws Exception {
        when(jwtService.parseToken("bad-token"))
                .thenThrow(new BusinessException(
                        ErrorCode.UNAUTHORIZED, "请先登录"));

        mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", "Bearer bad-token")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"问题"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED));

        verify(ragOrchestrator, never()).start(any(), any(), any());
    }

    @Test
    void sessionEndpointsReturnOwnedServiceDtosInApiResponse()
            throws Exception {
        authenticateUser();
        OffsetDateTime time = OffsetDateTime.parse(
                "2026-07-14T12:00:00+08:00");
        ChatSessionSummaryDto summary = new ChatSessionSummaryDto(
                12L, "绩点", time, time, "最后回答");
        PageResult<ChatSessionSummaryDto> page = new PageResult<>(
                List.of(summary), 1, 2, 10, 1);
        ChatSessionDetailDto detail = new ChatSessionDetailDto(
                12L,
                "绩点",
                List.of(new ChatMessageDto(
                        51L, "USER", "问题", List.of(), time)),
                true,
                time,
                time);
        when(chatService.listSessions(PRINCIPAL, 2, 10)).thenReturn(page);
        when(chatService.getSession(PRINCIPAL, 12L)).thenReturn(detail);

        mockMvc.perform(get("/api/chat/sessions")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(12))
                .andExpect(jsonPath("$.data.page").value(2));

        mockMvc.perform(get("/api/chat/sessions/12")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(12))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.messages[0].role").value("USER"));
    }

    @Test
    void malformedStreamJsonReturnsHttp400BeforeAsyncStarts() throws Exception {
        authenticateUser();

        mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.REQUEST_BODY_INVALID));

        verify(ragOrchestrator, never()).start(any(), any(), any());
    }

    @Test
    void invalidMaxSourcesReturnsHttp400BeforeAsyncStarts() throws Exception {
        authenticateUser();

        mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"问题","maxSources":21}
                                """))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.VALIDATION_FAILED));

        verify(ragOrchestrator, never()).start(any(), any(), any());
    }

    @Test
    void oneThousandEmojiQuestionIsAcceptedByHttpValidation()
            throws Exception {
        authenticateUser();
        when(chatProperties.streamTimeout()).thenReturn(Duration.ofSeconds(30));
        RecordingControl control = new RecordingControl();
        when(ragOrchestrator.start(any(), any(), any()))
                .thenAnswer(invocation -> {
                    ChatEventSink sink = invocation.getArgument(2);
                    sink.complete();
                    return control;
                });
        String question = "😀".repeat(1_000);

        MvcResult initial = mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk());

        ArgumentCaptor<ChatStreamRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatStreamRequest.class);
        verify(ragOrchestrator).start(
                any(), requestCaptor.capture(), any());
        String accepted = requestCaptor.getValue().question();
        assertThat(accepted.codePointCount(0, accepted.length()))
                .isEqualTo(1_000);
    }

    @Test
    void oneThousandAndOneEmojiQuestionReturnsHttp400()
            throws Exception {
        authenticateUser();
        String question = "😀".repeat(1_001);

        mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\"}"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.VALIDATION_FAILED));

        verify(ragOrchestrator, never()).start(any(), any(), any());
    }

    @Test
    void streamSerializesNamedErrorEventWithoutDone() throws Exception {
        authenticateUser();
        when(chatProperties.streamTimeout()).thenReturn(Duration.ofSeconds(30));
        RecordingControl control = new RecordingControl();
        when(ragOrchestrator.start(any(), any(), any()))
                .thenAnswer(invocation -> {
                    ChatEventSink sink = invocation.getArgument(2);
                    sink.meta(new ChatStreamMetaDto(
                            12L,
                            52L,
                            new IntentDto(IntentType.OFFICIAL, 0.9)));
                    sink.error(ApiResponse.error(
                            ErrorCode.MODEL_PROVIDER_FAILED,
                            "模型服务调用失败"));
                    sink.complete();
                    return control;
                });

        MvcResult initial = mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", AUTHORIZATION)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"问题"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn();

        String stream = completed.getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream.indexOf("event:meta"))
                .isLessThan(stream.indexOf("event:error"));
        assertThat(stream)
                .contains("\"code\":50010")
                .contains("模型服务调用失败")
                .doesNotContain("event:done");
        assertThat(control.cancelled).isTrue();
    }

    private void authenticateUser() {
        when(jwtService.parseToken("chat-token")).thenReturn(PRINCIPAL);
    }

    private static final class RecordingControl implements ChatStreamControl {

        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}

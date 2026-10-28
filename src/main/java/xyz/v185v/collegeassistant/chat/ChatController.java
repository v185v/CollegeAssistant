package xyz.v185v.collegeassistant.chat;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.rag.ChatStreamControl;
import xyz.v185v.collegeassistant.rag.RagOrchestrator;

/** 聊天流式问答与历史会话查询接口。 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final RagOrchestrator ragOrchestrator;
    private final ChatProperties chatProperties;

    public ChatController(
            ChatService chatService,
            RagOrchestrator ragOrchestrator,
            ChatProperties chatProperties
    ) {
        this.chatService = chatService;
        this.ragOrchestrator = ragOrchestrator;
        this.chatProperties = chatProperties;
    }

    /**
     * 创建 SSE 连接并启动一次 RAG 问答。
     *
     * @param principal 当前登录用户
     * @param request   问答请求
     * @return 与问答生命周期绑定的 SSE 发射器
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ChatStreamRequest request
    ) {
        ChatStreamRequest validated = request.validated();
        SseEmitter emitter = new SseEmitter(
                chatProperties.streamTimeout().toMillis());
        ChatStreamCancellationBridge cancellationBridge =
                new ChatStreamCancellationBridge();
        SseChatEventSink sink = new SseChatEventSink(
                emitter, cancellationBridge);
        ChatStreamControl control = ragOrchestrator.start(
                principal, validated, sink);
        cancellationBridge.attach(control);
        return emitter;
    }

    /** 分页查询当前用户的会话摘要。 */
    @GetMapping("/sessions")
    public ApiResponse<PageResult<ChatSessionSummaryDto>> listSessions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(chatService.listSessions(principal, page, size));
    }

    /** 查询当前用户拥有的指定会话及完整消息。 */
    @GetMapping("/sessions/{id}")
    public ApiResponse<ChatSessionDetailDto> getSession(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(chatService.getSession(principal, id));
    }

}

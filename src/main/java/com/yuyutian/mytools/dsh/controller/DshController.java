package com.yuyutian.mytools.dsh.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.dsh.model.DshModels;
import com.yuyutian.mytools.dsh.service.DshSessionService;
import com.yuyutian.mytools.dsh.service.DshEventHub;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * HarmonyOS App 使用的 DSH 受控语义接口。
 */
@RestController
@RequestMapping("/api/app/v1/dsh")
@RequiredArgsConstructor
public class DshController {

    private final DshSessionService sessionService;
    private final DshEventHub eventHub;

    /** 查询 DSH 连接状态。 @return DSH状态 */
    @GetMapping("/status")
    public Result<DshModels.Status> status() {
        return Result.success(sessionService.status());
    }

    /** 查询当前用户会话。 @param userId 用户ID @return 会话列表 */
    @GetMapping("/sessions")
    public Result<List<DshModels.Session>> sessions(@RequestAttribute("userId") Long userId) {
        return Result.success(sessionService.sessions(userId));
    }

    /** 创建当前用户会话。 @param userId 用户ID @param request 创建请求 @return 新会话 */
    @PostMapping("/sessions")
    public Result<DshModels.Session> create(@RequestAttribute("userId") Long userId,
                                            @Valid @RequestBody DshModels.CreateSessionRequest request) {
        return Result.success(sessionService.create(userId, request));
    }

    /** 读取会话历史。 @param userId 用户ID @param sessionId DSH会话ID @param beforeSeq 分页起点 @return 会话历史 */
    @GetMapping("/sessions/{sessionId}/history")
    public Result<DshModels.History> history(@RequestAttribute("userId") Long userId,
                                             @PathVariable String sessionId,
                                             @RequestParam(required = false) Long beforeSeq) {
        return Result.success(sessionService.history(userId, sessionId, beforeSeq));
    }

    /** 向会话发送消息。 @param userId 用户ID @param sessionId DSH会话ID @param request 消息请求 @return 接收回执 */
    @PostMapping("/sessions/{sessionId}/messages")
    public Result<DshModels.PromptReceipt> prompt(@RequestAttribute("userId") Long userId,
                                                  @PathVariable String sessionId,
                                                  @Valid @RequestBody DshModels.PromptRequest request) {
        return Result.success(sessionService.prompt(userId, sessionId, request));
    }

    /** 取消会话当前轮次。 @param userId 用户ID @param sessionId DSH会话ID @return 空结果 */
    @PostMapping("/sessions/{sessionId}/cancel")
    public Result<Void> cancel(@RequestAttribute("userId") Long userId, @PathVariable String sessionId) {
        sessionService.cancel(userId, sessionId);
        return Result.success();
    }

    /** 归档当前用户会话。 @param userId 用户ID @param sessionId DSH会话ID @return 空结果 */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> archive(@RequestAttribute("userId") Long userId, @PathVariable String sessionId) {
        sessionService.archive(userId, sessionId);
        return Result.success();
    }

    /** 订阅会话实时事件。 @param userId 用户ID @param sessionId DSH会话ID @return SSE发送器 */
    @GetMapping(value = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestAttribute("userId") Long userId, @PathVariable String sessionId) {
        return eventHub.subscribe(userId, sessionId);
    }

    /** 回复会话待处理授权。 @param userId 用户ID @param sessionId DSH会话ID @param interactionId 下行RPC标识 @param request 授权回复 @return 空结果 */
    @PostMapping("/sessions/{sessionId}/approvals/{interactionId}")
    public Result<Void> approval(@RequestAttribute("userId") Long userId, @PathVariable String sessionId,
                                 @PathVariable String interactionId,
                                 @Valid @RequestBody DshModels.ApprovalReplyRequest request) {
        eventHub.replyApproval(userId, sessionId, interactionId, request.allow());
        return Result.success();
    }
}

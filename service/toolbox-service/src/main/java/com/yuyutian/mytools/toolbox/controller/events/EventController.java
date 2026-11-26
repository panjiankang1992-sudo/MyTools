package com.yuyutian.mytools.toolbox.controller.events;

import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.model.EventBatchRequest;
import com.yuyutian.mytools.toolbox.model.EventBatchResult;
import com.yuyutian.mytools.toolbox.model.GuestSession;
import com.yuyutian.mytools.toolbox.service.events.EventIngestService;
import com.yuyutian.mytools.toolbox.service.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名使用事件上报接口：要求会话 cookie 与 CSRF 头同时有效，只接收最小字段集合。
 */
@RestController
@RequestMapping("/public/v1/events")
public class EventController {

    private final SessionService sessionService;
    private final EventIngestService eventIngestService;

    /**
     * 构造控制器。
     *
     * @param sessionService     会话服务
     * @param eventIngestService 事件写入服务
     */
    public EventController(SessionService sessionService, EventIngestService eventIngestService) {
        this.sessionService = sessionService;
        this.eventIngestService = eventIngestService;
    }

    /**
     * 接收一批匿名事件，逐条返回接受、重复或拒绝。
     *
     * @param request  批量请求体
     * @param servletRequest 当前请求，用于校验会话与 CSRF
     * @return 批量处理结果信封
     */
    @PostMapping
    public ApiResponse<EventBatchResult> ingest(@RequestBody EventBatchRequest request,
                                                HttpServletRequest servletRequest) {
        GuestSession session = sessionService.requireVerified(servletRequest);
        return ApiResponse.ok(eventIngestService.ingest(session, request));
    }
}

package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.InboundOutboxDeadCount;
import com.yuyutian.mytools.messaging.model.InboundOutboxRedriveView;
import com.yuyutian.mytools.messaging.service.InboundOutboxOperationsService;
import com.yuyutian.mytools.messaging.service.InternalRequestAuthorizer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 入站消息 Outbox 的内部运维接口。
 */
@RestController
@RequestMapping("/internal/v1/messaging-outbox/inbound")
public class InboundOutboxOperationsController {

    private final InternalRequestAuthorizer authorizer;
    private final InboundOutboxOperationsService service;

    /**
     * 创建入站 Outbox 运维接口。
     *
     * @param authorizer 内部请求鉴权器
     * @param service 运维服务
     */
    public InboundOutboxOperationsController(InternalRequestAuthorizer authorizer,
                                             InboundOutboxOperationsService service) {
        this.authorizer = authorizer;
        this.service = service;
    }

    /**
     * 查询当前入站死信数量。
     *
     * @param authorization 内部鉴权头
     * @return 死信计数
     */
    @GetMapping("/dead-count")
    public InboundOutboxDeadCount deadCount(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        authorizer.requireAuthorized(authorization);
        return service.deadCount();
    }

    /**
     * 精确重驱一个入站死信，保留原事件标识和载荷。
     *
     * @param authorization 内部鉴权头
     * @param eventId 事件标识
     * @return 当前事件状态
     */
    @PostMapping("/dead/{eventId}/redrive")
    public ResponseEntity<InboundOutboxRedriveView> redrive(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID eventId) {
        authorizer.requireAuthorized(authorization);
        return service.redrive(eventId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

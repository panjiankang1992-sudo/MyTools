package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.service.OutboundRetentionService;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

/** 新版副本清理仅允许独立管理身份读取或触发。 */
@RestController
@RequestMapping("/internal/v2/messaging/admin/retention")
public class OutboundRetentionController {
    private final OutboundRetentionService service;
    private final OutboundSecurity security;
    /** 注入清理服务及管理凭据校验器。 */
    public OutboundRetentionController(OutboundRetentionService service,OutboundSecurity security){this.service=service;this.security=security;}
    /** 聚合运行数据也不允许缓存。 */
    @ModelAttribute
    public void privateResponse(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");}
    /** 预览清理数量和未解决状态，不修改消息。 */
    @GetMapping
    public Map<String,Object> status(@RequestHeader("Authorization")String auth){security.authorize(auth,"admin");return service.status(Instant.now());}
    /** 独立处理明确截止的正文，普通保留关闭时仍可手工加速清理。 */
    @PostMapping("/deadlines/sweep")
    public Map<String,Integer> sweepDeadlines(@RequestHeader("Authorization")String auth){security.authorize(auth,"admin");return service.sweepDeadlines(Instant.now());}
    /** 显式执行有界批次，不触发任何渠道发送。 */
    @PostMapping("/sweep")
    public Map<String,Integer> sweep(@RequestHeader("Authorization")String auth){security.authorize(auth,"admin");return service.sweep(Instant.now());}
}

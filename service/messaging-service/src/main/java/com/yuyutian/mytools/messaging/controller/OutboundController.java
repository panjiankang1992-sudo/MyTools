package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.service.OutboundService;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** 新协议将管理员绑定、客户端业务和执行器权限分开校验。 */
@RestController
@RequestMapping("/internal/v2/messaging")
public class OutboundController {
    private final OutboundService service;
    private final OutboundSecurity security;
    /** 注入业务和凭据验证组件。 */
    public OutboundController(OutboundService service, OutboundSecurity security) { this.service = service; this.security = security; }
    /** 用户绑定和投递状态不允许被中间缓存保存。 */
    @ModelAttribute
    public void privateResponse(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store, private"); }
    /** 登记已经核验所有权的目标，不主动发送验证消息。 */
    @PostMapping("/admin/destinations")
    public Destination bind(@RequestHeader("Authorization") String auth, @Valid @RequestBody Binding request) {
        security.authorize(auth, "admin"); return service.bind(request);
    }
    /** 获取调用方拥有的目的地。 */
    @GetMapping("/destinations")
    public List<Destination> destinations(@RequestHeader("Authorization") String auth, @RequestParam long ownerId) {
        security.authorize(auth, "client"); return service.destinations(ownerId);
    }
    /** 停用本人目标。 */
    @PostMapping("/destinations/{id}/disable")
    public Destination disable(@RequestHeader("Authorization") String auth, @RequestParam long ownerId, @PathVariable UUID id) {
        security.authorize(auth, "client"); return service.disable(ownerId, id);
    }
    /** 创建冻结的异步投递。 */
    @PostMapping("/deliveries")
    public View create(@RequestHeader("Authorization") String auth, @Valid @RequestBody Create request) {
        security.authorize(auth, "client"); return service.create(request);
    }
    /** 丢失创建响应时，取消请求仍使用同一键并阻止任何迟到发送。 */
    @PostMapping("/deliveries/cancel-by-key")
    public View cancelByKey(@RequestHeader("Authorization") String auth, @Valid @RequestBody Create request) {
        security.authorize(auth, "client"); return service.cancelByKey(request);
    }
    /** 正文已到期时只接受严格身份摘要，客户端仍必须有同一所有者权限。 */
    @PostMapping("/deliveries/cancel-by-identity")
    public View cancelByIdentity(@RequestHeader("Authorization") String auth,@Valid @RequestBody CancelIdentity request) {
        security.authorize(auth,"client");return service.cancelByIdentity(request);
    }
    /** 查询本人状态。 */
    @GetMapping("/deliveries/{id}")
    public View get(@RequestHeader("Authorization") String auth, @RequestParam long ownerId, @PathVariable UUID id) {
        security.authorize(auth, "client"); return service.get(ownerId, id);
    }
    /** 请求取消尚未发送的投递。 */
    @PostMapping("/deliveries/{id}/cancel")
    public View cancel(@RequestHeader("Authorization") String auth, @RequestParam long ownerId, @PathVariable UUID id) {
        security.authorize(auth, "client"); return service.cancel(ownerId, id);
    }
    /** 显式重试，未知状态需要确认重复风险。 */
    @PostMapping("/deliveries/{id}/retry")
    public View retry(@RequestHeader("Authorization") String auth, @RequestParam long ownerId, @PathVariable UUID id, @Valid @RequestBody Retry request) {
        security.authorize(auth, "client"); return service.retry(ownerId, id, request);
    }
    /** 根据已获得的提供方证据进行人工对账。 */
    @PostMapping("/admin/deliveries/{id}/resolve")
    public View resolve(@RequestHeader("Authorization") String auth, @RequestParam long ownerId, @PathVariable UUID id, @Valid @RequestBody Resolution request) {
        security.authorize(auth, "admin"); return service.resolve(ownerId, id, request);
    }
    /** 执行器必须同时提供在线任务租约。 */
    @PostMapping("/jobs/deliveries/{id}/execute")
    public View execute(@RequestHeader("Authorization") String auth, @PathVariable UUID id, @RequestParam int generation,
            @RequestHeader("X-Task-Execution-Id") UUID executionId, @RequestHeader("X-Task-Lease-Token") UUID leaseToken) {
        security.authorize(auth, "worker"); return service.execute(id, generation, new Proof(executionId, leaseToken));
    }
}

package com.yuyutian.mytools.automation.controller;

import com.yuyutian.mytools.automation.model.AutomationRuleRecord;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import com.yuyutian.mytools.automation.model.ClaimMessageLinksRequest;
import com.yuyutian.mytools.automation.model.ClaimMessageLinksResponse;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import com.yuyutian.mytools.automation.model.ProcessMessageRequest;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import com.yuyutian.mytools.automation.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.automation.service.MessageAutomationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 消息自动化内部管理和事件处理接口。
 */
@RestController
@RequestMapping("/internal/v1")
public class AutomationController {

    private final MessageAutomationService service;
    private final AutomationRepository repository;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建消息自动化控制器。
     */
    public AutomationController(MessageAutomationService service, AutomationRepository repository,
                                InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.repository = repository;
        this.authorizer = authorizer;
    }

    /**
     * 创建授权规则和固定下载动作绑定。
     */
    @PostMapping("/automation-rules")
    public AutomationRuleRecord createRule(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateAutomationRuleRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.createRule(request);
    }

    /**
     * 幂等处理一个只携带消息标识的事件。
     */
    @PostMapping("/message-events")
    public ResponseEntity<AutomationRunView> process(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProcessMessageRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.process(request.messageId()));
    }

    /**
     * 为消息派生任务批量登记最小来源链接。
     */
    @PostMapping("/processed-links/claims")
    public ClaimMessageLinksResponse claimLinks(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody ClaimMessageLinksRequest request) {
        authorizer.requireAuthorized(authorization);
        return new ClaimMessageLinksResponse(service.claimLinks(request));
    }

    /**
     * 查询消息对应的自动化运行。
     */
    @GetMapping("/automation-runs/by-message/{messageId}")
    public ResponseEntity<AutomationRunView> get(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID messageId) {
        authorizer.requireAuthorized(authorization);
        if (repository.findRun(messageId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.get(messageId));
    }

    /**
     * 级联取消自动化运行中的子动作。
     */
    @PostMapping("/automation-runs/{runId}/cancel")
    public AutomationRunView cancel(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID runId) {
        authorizer.requireAuthorized(authorization);
        return service.cancel(runId);
    }
}

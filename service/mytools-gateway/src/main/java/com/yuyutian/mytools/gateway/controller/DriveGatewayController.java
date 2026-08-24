package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.OperationView;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.RefreshIndexRequest;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.AccountSummary;
import com.yuyutian.mytools.gateway.model.DriveGatewayModels.CopyObjectRequest;
import com.yuyutian.mytools.gateway.service.DriveGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从可信主体注入 owner 的 Drive Gateway 路由。
 */
@RestController
@Validated
@RequestMapping("/api/app/v1/drive")
public class DriveGatewayController {
    private final GatewayProperties properties;
    private final DriveGatewayClient client;

    /**
     * 创建 Drive Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client Drive 客户端
     */
    public DriveGatewayController(GatewayProperties properties, DriveGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 查询当前主体拥有账户的直接子项。
     *
     * @param accountId Drive 账户标识
     * @param parentPath 父路径
     * @param request HTTP 请求
     * @return 索引子项
     */
    @GetMapping("/accounts/{accountId}/items")
    public List<Map<String, Object>> items(@PathVariable UUID accountId,
            @RequestParam(defaultValue = "") @Size(max = 2048) String parentPath,
            HttpServletRequest request) {
        GatewayPrincipal principal = requireAllowed(request);
        return client.items(accountId, principal.userId(), parentPath, correlation(request));
    }

    /** 查询当前主体的 Drive 账户。 @param request HTTP 请求 @return 账户摘要 */
    @GetMapping("/accounts")
    public List<AccountSummary> accounts(HttpServletRequest request) {
        GatewayPrincipal principal=requireAllowed(request);
        return client.accounts(principal.userId(),correlation(request));
    }

    /** 创建账户索引刷新任务。 @param accountId 账户标识 @param body 请求 @param request HTTP 请求 @return 操作 */
    @PostMapping("/accounts/{accountId}/refresh-index") @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView refreshIndex(@PathVariable UUID accountId,@Valid @RequestBody RefreshIndexRequest body,
                                      HttpServletRequest request) {
        GatewayPrincipal principal=requireAllowed(request);
        return client.refreshIndex(accountId,principal.userId(),body,correlation(request));
    }

    /**
     * 创建受控对象复制操作。
     *
     * @param accountId 来源账户
     * @param body 复制请求
     * @param request HTTP 请求
     * @return 操作
     */
    @PostMapping("/accounts/{accountId}/copy-object")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView copyObject(@PathVariable UUID accountId, @Valid @RequestBody CopyObjectRequest body,
                                    HttpServletRequest request) {
        GatewayPrincipal principal = requireAllowed(request);
        return client.copyObject(accountId, principal.userId(), body, correlation(request));
    }

    /** 查询索引刷新操作。 @param operationId 操作标识 @param request HTTP 请求 @return 操作 */
    @GetMapping("/operations/{operationId}")
    public OperationView operation(@PathVariable UUID operationId,HttpServletRequest request) {
        GatewayPrincipal principal=requireAllowed(request);
        return client.operation(operationId,principal.userId(),correlation(request));
    }

    /** 取消索引刷新操作。 @param operationId 操作标识 @param request HTTP 请求 @return 操作 */
    @PostMapping("/operations/{operationId}/cancel")
    public OperationView cancel(@PathVariable UUID operationId,HttpServletRequest request) {
        GatewayPrincipal principal=requireAllowed(request);
        return client.cancel(operationId,principal.userId(),correlation(request));
    }

    private GatewayPrincipal requireAllowed(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            if (!properties.driveRouteEnabled()) {
                throw new GatewayRouteDisabledException();
            }
            throw new GatewayUnauthorizedException();
        }
        if (!properties.driveTenantAllowed(principal.userId())) {
            throw new GatewayRouteDisabledException();
        }
        return principal;
    }

    private String correlation(HttpServletRequest request) {
        return request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE).toString();
    }
}

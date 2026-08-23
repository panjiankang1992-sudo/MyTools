package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.DriveGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

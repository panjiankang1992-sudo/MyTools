package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.LoginRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.RefreshRequest;
import com.yuyutian.mytools.gateway.model.IdentityGatewayModels.TokenPair;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.IdentityGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 默认关闭的 Identity 登录和刷新入口。
 */
@RestController
@RequestMapping("/api/app/v1/identity")
public class IdentityGatewayController {
    private final GatewayProperties properties;
    private final IdentityGatewayClient client;

    /**
     * 创建 Identity Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client Identity 客户端
     */
    public IdentityGatewayController(GatewayProperties properties, IdentityGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 使用独立身份服务登录。
     *
     * @param request 登录请求
     * @param servletRequest HTTP 请求
     * @return 令牌对
     */
    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        requireEnabled();
        return client.login(request, correlation(servletRequest));
    }

    /**
     * 使用独立身份服务轮换刷新令牌。
     *
     * @param request 刷新请求
     * @param servletRequest HTTP 请求
     * @return 新令牌对
     */
    @PostMapping("/refresh")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest servletRequest) {
        requireEnabled();
        return client.refresh(request, correlation(servletRequest));
    }

    private void requireEnabled() {
        if (!properties.identityRouteUsable()) {
            throw new GatewayRouteDisabledException();
        }
    }

    private String correlation(HttpServletRequest request) {
        return request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE).toString();
    }
}

package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.AppCatalogGatewayProperties;
import com.yuyutian.mytools.gateway.model.AppCatalogGatewayModels.CatalogView;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.AppCatalogGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 从可信主体读取应用目录。
 */
@RestController
@RequestMapping("/api/app/v1/catalog")
public class AppCatalogGatewayController {
    private final AppCatalogGatewayProperties properties;
    private final AppCatalogGatewayClient client;

    /**
     * 创建应用目录 Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client 应用目录客户端
     */
    public AppCatalogGatewayController(AppCatalogGatewayProperties properties, AppCatalogGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 查询当前主体的应用目录。
     *
     * @param request HTTP 请求
     * @return 目录摘要
     */
    @GetMapping
    public List<CatalogView> list(HttpServletRequest request) {
        requirePrincipal(request);
        return client.list(correlation(request));
    }

    private GatewayPrincipal requirePrincipal(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            if (!properties.routeEnabled()) {
                throw new GatewayRouteDisabledException();
            }
            throw new GatewayUnauthorizedException();
        }
        return principal;
    }

    private String correlation(HttpServletRequest request) {
        return request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE).toString();
    }
}

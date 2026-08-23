package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.CreateHttpDownload;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.DownloadView;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.ResultSummary;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.DownloadGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayBadRequestException;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * 从可信主体注入 owner 的下载生命周期路由。
 */
@RestController
@Validated
@RequestMapping("/api/app/v1/downloads")
public class DownloadGatewayController {
    private final GatewayProperties properties;
    private final DownloadGatewayClient client;

    /**
     * 创建下载 Gateway 控制器。
     */
    public DownloadGatewayController(GatewayProperties properties, DownloadGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 创建 HTTPS 资源下载。
     */
    @PostMapping("/http")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DownloadView createHttp(@Valid @RequestBody CreateHttpDownload body,
                                   HttpServletRequest request) {
        validateSource(body);
        GatewayPrincipal principal = requireAllowed(request);
        return client.createHttp(body, principal.userId(), correlation(request));
    }

    /**
     * 查询下载状态。
     */
    @GetMapping("/{requestId}")
    public DownloadView get(@PathVariable UUID requestId, HttpServletRequest request) {
        GatewayPrincipal principal = requireAllowed(request);
        return client.get(requestId, principal.userId(), correlation(request));
    }

    /**
     * 查询下载结果摘要。
     */
    @GetMapping("/{requestId}/result-summary")
    public ResultSummary summary(@PathVariable UUID requestId, HttpServletRequest request) {
        GatewayPrincipal principal = requireAllowed(request);
        return client.summary(requestId, principal.userId(), correlation(request));
    }

    /**
     * 取消下载任务。
     */
    @PostMapping("/{requestId}/cancel")
    public DownloadView cancel(@PathVariable UUID requestId, HttpServletRequest request) {
        GatewayPrincipal principal = requireAllowed(request);
        return client.cancel(requestId, principal.userId(), correlation(request));
    }

    private void validateSource(CreateHttpDownload body) {
        try {
            URI source = URI.create(body.url());
            if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null) {
                throw new GatewayBadRequestException();
            }
        } catch (IllegalArgumentException exception) {
            throw new GatewayBadRequestException();
        }
        if (body.fileName().chars().anyMatch(value -> value < 32)
                || body.fileName().contains("/") || body.fileName().contains("\\")) {
            throw new GatewayBadRequestException();
        }
    }

    private GatewayPrincipal requireAllowed(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            if (!properties.downloadRouteEnabled()) {
                throw new GatewayRouteDisabledException();
            }
            throw new GatewayUnauthorizedException();
        }
        if (!properties.downloadTenantAllowed(principal.userId())) {
            throw new GatewayRouteDisabledException();
        }
        return principal;
    }

    private String correlation(HttpServletRequest request) {
        return request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE).toString();
    }
}

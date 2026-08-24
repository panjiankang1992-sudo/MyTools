package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaPage;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressRequest;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressView;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.MediaGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 从可信主体注入 owner 的 Media Gateway 路由。
 */
@RestController
@RequestMapping("/api/app/v1/media")
public class MediaGatewayController {
    private final GatewayProperties properties;
    private final MediaGatewayClient client;

    /**
     * 创建 Media Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client Media 客户端
     */
    public MediaGatewayController(GatewayProperties properties, MediaGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 分页查询当前主体媒体。
     *
     * @param afterId 起始标识
     * @param includeMissing 是否包含缺失项
     * @param limit 页大小
     * @param request HTTP 请求
     * @return 媒体页
     */
    @GetMapping("/items")
    public MediaPage list(@RequestParam(required = false) UUID afterId,
                          @RequestParam(defaultValue = "false") boolean includeMissing,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                          HttpServletRequest request) {
        GatewayPrincipal principal = requireEnabled(request);
        return client.list(principal.userId(), afterId, includeMissing, limit, correlation(request));
    }

    /**
     * 查询当前主体媒体。
     *
     * @param mediaId 媒体标识
     * @param request HTTP 请求
     * @return 媒体
     */
    @GetMapping("/items/{mediaId}")
    public MediaView view(@PathVariable UUID mediaId, HttpServletRequest request) {
        GatewayPrincipal principal = requireEnabled(request);
        return client.view(principal.userId(), mediaId, correlation(request));
    }

    /**
     * 写入当前主体播放进度。
     *
     * @param mediaId 媒体标识
     * @param body 进度请求
     * @param request HTTP 请求
     * @return 新进度
     */
    @PutMapping("/items/{mediaId}/progress")
    public ProgressView progress(@PathVariable UUID mediaId,
                                 @Valid @RequestBody ProgressRequest body,
                                 HttpServletRequest request) {
        GatewayPrincipal principal = requireEnabled(request);
        return client.progress(principal.userId(), mediaId, body, correlation(request));
    }

    private GatewayPrincipal requireEnabled(HttpServletRequest request) {
        if (!properties.mediaRouteEnabled()) {
            throw new GatewayRouteDisabledException();
        }
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            throw new GatewayUnauthorizedException();
        }
        return principal;
    }

    private String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE);
        return value == null ? UUID.randomUUID().toString() : value.toString();
    }
}

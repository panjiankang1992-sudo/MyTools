package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaPage;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.EbookPage;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressRequest;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartDirectoryScan;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.OperationView;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartAnalysis;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.MediaGatewayClient;
import com.yuyutian.mytools.gateway.service.MediaPlaybackTicketService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 从可信主体注入 owner 的 Media Gateway 路由。
 */
@RestController
@RequestMapping("/api/app/v1/media")
public class MediaGatewayController {
    private final GatewayProperties properties;
    private final MediaGatewayClient client;
    private final MediaPlaybackTicketService tickets;

    /**
     * 创建 Media Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client Media 客户端
     */
    public MediaGatewayController(GatewayProperties properties, MediaGatewayClient client,
                                  MediaPlaybackTicketService tickets) {
        this.properties = properties;
        this.client = client;
        this.tickets = tickets;
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
                          @RequestParam(required = false) String mimePrefix,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                          HttpServletRequest request) {
        GatewayPrincipal principal = requireEnabled(request);
        return client.list(principal.userId(), afterId, includeMissing, mimePrefix, limit, correlation(request));
    }

    /** 查询当前主体 EBOOK 目录电子书。 @param page 页码 @param pageSize 页大小 @param keyword 关键字 @param request HTTP 请求 @return 页面 */
    @GetMapping("/ebooks")
    public EbookPage ebooks(@RequestParam(defaultValue = "1") @Min(1) int page,
                            @RequestParam(defaultValue = "40") @Min(1) @Max(100) int pageSize,
                            @RequestParam(defaultValue = "") String keyword, HttpServletRequest request) {
        GatewayPrincipal principal = requireEnabled(request);
        return client.ebooks(principal.userId(), principal.username(), page, pageSize, keyword, correlation(request));
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

    /** 删除当前主体媒体投影。 @param mediaId 媒体标识 @param request HTTP 请求 */
    @DeleteMapping("/items/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID mediaId, HttpServletRequest request) {
        GatewayPrincipal principal = requireEnabled(request);
        client.delete(principal.userId(), mediaId, correlation(request));
    }

    /** 读取图片原文件作为图库缩略图。 */
    @GetMapping("/items/{mediaId}/thumbnail")
    public void thumbnail(@PathVariable UUID mediaId, HttpServletRequest request, HttpServletResponse response) {
        GatewayPrincipal principal = requireEnabled(request);
        client.stream(principal.userId(), mediaId, true, null, response, correlation(request));
    }

    /** 读取认证媒体原文件。 */
    @GetMapping("/items/{mediaId}/content")
    public void content(@PathVariable UUID mediaId, HttpServletRequest request, HttpServletResponse response) {
        GatewayPrincipal principal = requireEnabled(request);
        client.stream(principal.userId(), mediaId, false, request.getHeader("Range"), response, correlation(request));
    }

    /** 为播放器签发短期无头部读取票据。 */
    @PostMapping("/items/{mediaId}/play-ticket")
    public Map<String, Object> playTicket(@PathVariable UUID mediaId, HttpServletRequest request) {
        GatewayPrincipal principal = requireEnabled(request);
        // 签发前读取一次媒体，确保租户确实拥有目标资源。
        client.view(principal.userId(), mediaId, correlation(request));
        var ticket = tickets.issue(principal.userId(), mediaId);
        return Map.of("ticket", ticket.token(),
                "streamPath", "/api/app/v1/media/tickets/" + ticket.token(),
                "expiresAt", ticket.expiresAt().toString());
    }

    /** 使用短期票据流式播放媒体。 */
    @GetMapping("/tickets/{ticket}")
    public void play(@PathVariable String ticket, HttpServletRequest request, HttpServletResponse response) {
        var value = tickets.require(ticket);
        client.stream(value.ownerId(), value.mediaId(), false, request.getHeader("Range"), response, correlation(request));
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

    /** 创建目录扫描任务。 @param body 请求 @param request HTTP 请求 @return 操作 */
    @PostMapping("/operations/directory-scans") @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView startDirectoryScan(@Valid @RequestBody StartDirectoryScan body,HttpServletRequest request) {
        GatewayPrincipal principal=requireEnabled(request);
        return client.startDirectoryScan(principal.userId(),body,correlation(request));
    }

    /** 创建当前主体的媒体分析任务。 @param mediaId 媒体标识 @param body 请求 @param request HTTP 请求 @return 操作 */
    @PostMapping("/items/{mediaId}/analysis-operations") @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView startAnalysis(@PathVariable UUID mediaId,@Valid @RequestBody StartAnalysis body,
                                       HttpServletRequest request) {
        GatewayPrincipal principal=requireEnabled(request);
        return client.startAnalysis(principal.userId(),mediaId,body,correlation(request));
    }

    /** 查询媒体操作。 @param operationId 操作 @param request HTTP 请求 @return 操作 */
    @GetMapping("/operations/{operationId}")
    public OperationView operation(@PathVariable UUID operationId,HttpServletRequest request) {
        GatewayPrincipal principal=requireEnabled(request);
        return client.operation(principal.userId(),operationId,correlation(request));
    }

    /** 取消媒体操作。 @param operationId 操作 @param request HTTP 请求 @return 操作 */
    @PostMapping("/operations/{operationId}/cancel")
    public OperationView cancel(@PathVariable UUID operationId,HttpServletRequest request) {
        GatewayPrincipal principal=requireEnabled(request);
        return client.cancel(principal.userId(),operationId,correlation(request));
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

package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从可信主体注入 owner 的 Reader Gateway 路由。
 */
@RestController
@RequestMapping("/api/app/v1/reader")
public class ReaderGatewayController {

    private final GatewayProperties properties;
    private final ReaderGatewayClient client;

    /**
     * 创建 Reader Gateway 控制器。
     */
    public ReaderGatewayController(GatewayProperties properties, ReaderGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 查询当前主体书架。
     */
    @GetMapping("/shelves")
    public List<Map<String, Object>> shelves(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                             HttpServletRequest request) {
        return list("shelves", includeDeleted, request);
    }

    /**
     * 写入当前主体书架。
     */
    @PostMapping("/shelves")
    public Map<String, Object> saveShelf(@Valid @RequestBody ShelfRequest body, HttpServletRequest request) {
        requireEnabled();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", principal(request).userId());
        payload.put("bookKey", body.bookKey());
        payload.put("metadata", body.metadata());
        payload.put("deleted", body.deleted());
        payload.put("expectedVersion", body.expectedVersion());
        return save("shelves", payload, request);
    }

    /**
     * 查询当前主体阅读进度。
     */
    @GetMapping("/progress")
    public List<Map<String, Object>> progress(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                              HttpServletRequest request) {
        return list("progress", includeDeleted, request);
    }

    /**
     * 写入当前主体阅读进度。
     */
    @PostMapping("/progress")
    public Map<String, Object> saveProgress(@Valid @RequestBody ProgressRequest body, HttpServletRequest request) {
        requireEnabled();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", principal(request).userId());
        payload.put("bookKey", body.bookKey());
        payload.put("chapterIndex", body.chapterIndex());
        payload.put("chapterUrl", body.chapterUrl());
        payload.put("position", body.position());
        payload.put("deleted", body.deleted());
        payload.put("expectedVersion", body.expectedVersion());
        return save("progress", payload, request);
    }

    /**
     * 查询当前主体阅读标记。
     */
    @GetMapping("/markers")
    public List<Map<String, Object>> markers(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                             HttpServletRequest request) {
        return list("markers", includeDeleted, request);
    }

    /**
     * 写入当前主体阅读标记。
     */
    @PostMapping("/markers")
    public Map<String, Object> saveMarker(@Valid @RequestBody MarkerRequest body, HttpServletRequest request) {
        requireEnabled();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("markerId", body.markerId());
        payload.put("ownerId", principal(request).userId());
        payload.put("bookKey", body.bookKey());
        payload.put("markerType", body.markerType());
        payload.put("chapterIndex", body.chapterIndex());
        payload.put("position", body.position());
        payload.put("note", body.note());
        payload.put("deleted", body.deleted());
        payload.put("expectedVersion", body.expectedVersion());
        return save("markers", payload, request);
    }

    private List<Map<String, Object>> list(String resource, boolean includeDeleted, HttpServletRequest request) {
        requireEnabled();
        return client.list(resource, principal(request).userId(), includeDeleted, correlation(request));
    }

    private Map<String, Object> save(String resource, Map<String, Object> payload, HttpServletRequest request) {
        requireEnabled();
        return client.save(resource, payload, correlation(request));
    }

    private GatewayPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            throw new GatewayUnauthorizedException();
        }
        return principal;
    }

    private String correlation(HttpServletRequest request) {
        return request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE).toString();
    }

    private void requireEnabled() {
        if (!properties.readerRouteEnabled()) {
            throw new GatewayRouteDisabledException();
        }
    }

    /**
     * Gateway 书架写入请求，不允许客户端指定 owner。
     */
    public record ShelfRequest(@NotBlank @Size(max = 512) String bookKey,
                               @NotNull Map<String, Object> metadata,
                               boolean deleted, @Positive Long expectedVersion) {
    }

    /**
     * Gateway 阅读进度写入请求，不允许客户端指定 owner。
     */
    public record ProgressRequest(@NotBlank @Size(max = 512) String bookKey, @Min(0) int chapterIndex,
                                  @Size(max = 4096) String chapterUrl, @NotNull Map<String, Object> position,
                                  boolean deleted, @Positive Long expectedVersion) {
    }

    /**
     * Gateway 阅读标记写入请求，不允许客户端指定 owner。
     */
    public record MarkerRequest(@NotNull UUID markerId, @NotBlank @Size(max = 512) String bookKey,
                                @NotBlank @Size(max = 32) String markerType, @Min(0) int chapterIndex,
                                @NotNull Map<String, Object> position, @Size(max = 10000) String note,
                                boolean deleted, @Positive Long expectedVersion) {
    }
}

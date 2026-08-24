package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateDiscoveryRequest;
import com.yuyutian.mytools.reader.model.DiscoveryView;
import com.yuyutian.mytools.reader.model.SourceIngestRequest;
import com.yuyutian.mytools.reader.model.SourceIngestResult;
import com.yuyutian.mytools.reader.service.SourceDiscoveryService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/**
 * 书源发现公开编排接口与内部写入接口。
 */
@RestController
public class SourceDiscoveryController {

    private final SourceDiscoveryService discoveryService;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建书源发现控制器。
     *
     * @param discoveryService 发现服务
     * @param authorizer 内部请求校验器
     */
    public SourceDiscoveryController(SourceDiscoveryService discoveryService,
                                     InternalRequestAuthorizer authorizer) {
        this.discoveryService = discoveryService;
        this.authorizer = authorizer;
    }

    /**
     * 创建异步书源发现任务。
     *
     * @param request 创建请求
     * @return 已受理任务
     */
    @PostMapping("/api/v1/source-discoveries")
    public ResponseEntity<DiscoveryView> create(@Valid @RequestBody CreateDiscoveryRequest request) {
        return ResponseEntity.accepted().body(discoveryService.create(request));
    }

    /**
     * 查询书源发现任务。
     *
     * @param id 请求标识
     * @param ownerId 可选所有者标识
     * @return 任务视图
     */
    @GetMapping("/api/v1/source-discoveries/{id}")
    public DiscoveryView get(@PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        return ownerId == null ? discoveryService.get(id) : discoveryService.get(id, ownerId);
    }

    /**
     * 取消书源发现任务。
     *
     * @param id 请求标识
     * @param ownerId 可选所有者标识
     * @return 任务视图
     */
    @PostMapping("/api/v1/source-discoveries/{id}/cancel")
    public DiscoveryView cancel(@PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        return ownerId == null ? discoveryService.cancel(id) : discoveryService.cancel(id, ownerId);
    }

    /**
     * 接收 Executor 分批发现的书源快照。
     *
     * @param id 请求标识
     * @param authorization 内部授权头
     * @param request 批次请求
     * @return 写入摘要
     */
    @PostMapping("/api/internal/v1/source-discoveries/{id}/sources")
    public SourceIngestResult ingest(@PathVariable UUID id,
                                     @RequestHeader("Authorization") String authorization,
                                     @Valid @RequestBody SourceIngestRequest request) {
        authorizer.requireAuthorized(authorization);
        return discoveryService.ingest(id, request);
    }
}

package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.model.CreateProviderRequest;
import com.yuyutian.mytools.storage.model.ProviderView;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageProviderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 内部远端存储 Provider 接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/providers")
public class StorageProviderController {
    private final StorageProviderService providerService;
    private final InternalAuthorizer authorizer;

    /**
     * 创建 Provider 控制器。
     *
     * @param providerService Provider 服务
     * @param authorizer 内部鉴权器
     */
    public StorageProviderController(StorageProviderService providerService, InternalAuthorizer authorizer) {
        this.providerService = providerService;
        this.authorizer = authorizer;
    }

    /**
     * 幂等注册远端 Provider。
     *
     * @param authorization 内部授权头
     * @param request 创建请求
     * @return 安全 Provider 视图
     */
    @PostMapping
    public ResponseEntity<ProviderView> create(@RequestHeader("Authorization") String authorization,
                                               @Valid @RequestBody CreateProviderRequest request) {
        authorizer.require(authorization);
        return ResponseEntity.accepted().body(providerService.create(request));
    }

    /**
     * 同步列出一个远端目录。
     *
     * @param providerId Provider 标识
     * @param path 相对路径
     * @param authorization 内部授权头
     * @return 标准化对象列表
     */
    @GetMapping("/{providerId}/objects")
    public List<RemoteObjectView> list(@PathVariable UUID providerId,
                                       @RequestParam(defaultValue = "") String path,
                                       @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return providerService.list(providerId, path);
    }

    /**
     * 流式读取一个远端 Provider 对象。
     *
     * @param providerId Provider 标识
     * @param path 相对路径
     * @param maximumBytes 最大字节数
     * @param range HTTP Range
     * @param authorization 内部授权头
     * @return 对象流
     */
    @GetMapping("/{providerId}/objects/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID providerId,
            @RequestParam String path,
            @RequestParam(defaultValue = "21474836480") long maximumBytes,
            @RequestHeader(value = "Range", required = false) String range,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        var content = providerService.content(providerId, path, maximumBytes, range);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(content.statusCode())
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (content.contentLength() >= 0) {
            response.contentLength(content.contentLength());
        }
        if (content.contentRange() != null) response.header("Content-Range", content.contentRange());
        if (content.acceptRanges() != null) response.header("Accept-Ranges", content.acceptRanges());
        return response.body(new InputStreamResource(content.stream()));
    }
}

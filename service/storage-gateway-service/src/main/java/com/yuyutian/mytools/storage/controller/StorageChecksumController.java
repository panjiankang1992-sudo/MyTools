package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.model.ChecksumOperation;
import com.yuyutian.mytools.storage.model.CreateChecksumOperationRequest;
import com.yuyutian.mytools.storage.model.FinishChecksumOperationRequest;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageChecksumService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/**
 * 本地受管对象校验和任务接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/checksum-operations")
public class StorageChecksumController {
    private final StorageChecksumService checksumService;
    private final InternalAuthorizer authorizer;

    /**
     * 创建校验和控制器。
     *
     * @param checksumService 校验和服务
     * @param authorizer 内部鉴权器
     */
    public StorageChecksumController(StorageChecksumService checksumService, InternalAuthorizer authorizer) {
        this.checksumService = checksumService;
        this.authorizer = authorizer;
    }

    /**
     * 创建校验和任务。
     *
     * @param authorization 授权头
     * @param request 创建请求
     * @return 操作
     */
    @PostMapping
    public ResponseEntity<ChecksumOperation> create(@RequestHeader("Authorization") String authorization,
                                                     @Valid @RequestBody CreateChecksumOperationRequest request) {
        authorizer.require(authorization);
        return ResponseEntity.accepted().body(checksumService.create(request));
    }

    /**
     * 查询校验和操作。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 操作
     */
    @GetMapping("/{id}")
    public ChecksumOperation get(@PathVariable UUID id, @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return checksumService.require(id);
    }

    /**
     * 为亲和节点上的执行器流式提供对象。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 对象流
     * @throws IOException 无法打开对象
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) throws IOException {
        authorizer.require(authorization);
        var object = checksumService.content(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(object.size()).body(new InputStreamResource(Files.newInputStream(object.path())));
    }

    /**
     * 写入校验和终态。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @param request 完成请求
     * @return 最新操作
     */
    @PostMapping("/{id}/finish")
    public ChecksumOperation finish(@PathVariable UUID id, @RequestHeader("Authorization") String authorization,
                                    @Valid @RequestBody FinishChecksumOperationRequest request) {
        authorizer.require(authorization);
        return checksumService.finish(id, request);
    }
}

package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.model.CreateUploadRequest;
import com.yuyutian.mytools.storage.model.UploadView;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/**
 * Executor 使用的内部受控上传接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/uploads")
public class StorageUploadController {

    private final StorageUploadService uploadService;
    private final InternalAuthorizer authorizer;

    /**
     * 创建存储上传控制器。
     *
     * @param uploadService 上传服务
     * @param authorizer 内部鉴权器
     */
    public StorageUploadController(StorageUploadService uploadService, InternalAuthorizer authorizer) {
        this.uploadService = uploadService;
        this.authorizer = authorizer;
    }

    /**
     * 创建幂等上传会话。
     *
     * @param authorization 内部授权头
     * @param request 创建请求
     * @return 已创建会话
     */
    @PostMapping
    public ResponseEntity<UploadView> create(@RequestHeader("Authorization") String authorization,
                                             @Valid @RequestBody CreateUploadRequest request) {
        authorizer.require(authorization);
        return ResponseEntity.accepted().body(uploadService.create(request));
    }

    /**
     * 流式上传并原子发布内容。
     *
     * @param id 上传标识
     * @param authorization 内部授权头
     * @param request Servlet 请求
     * @return 完成会话
     * @throws IOException 无法读取请求流
     */
    @PutMapping("/{id}/content")
    public UploadView upload(@PathVariable UUID id, @RequestHeader("Authorization") String authorization,
                             HttpServletRequest request) throws IOException {
        authorizer.require(authorization);
        return uploadService.upload(id, request.getInputStream());
    }

    /**
     * 查询上传状态。
     *
     * @param id 上传标识
     * @param authorization 内部授权头
     * @return 上传视图
     */
    @GetMapping("/{id}")
    public UploadView get(@PathVariable UUID id, @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return uploadService.get(id);
    }
}

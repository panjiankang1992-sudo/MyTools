package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.config.StorageProperties;
import com.yuyutian.mytools.storage.model.CreateUploadRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.UploadView;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Executor 使用的内部受控上传接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/uploads")
public class StorageUploadController {

    private final StorageUploadService uploadService;
    private final StorageProperties properties;

    /**
     * 创建存储上传控制器。
     *
     * @param uploadService 上传服务
     * @param properties 存储配置
     */
    public StorageUploadController(StorageUploadService uploadService, StorageProperties properties) {
        this.uploadService = uploadService;
        this.properties = properties;
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
        requireToken(authorization);
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
        requireToken(authorization);
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
        requireToken(authorization);
        return uploadService.get(id);
    }

    private void requireToken(String authorization) {
        String expected = properties.internalToken();
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (expected == null || expected.isBlank() || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(ErrorCode.INTERNAL_UNAUTHORIZED.code());
        }
    }
}

package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.config.StorageProperties;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.service.StorageObjectService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * 内部受管对象流式读取接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/objects")
public class StorageObjectController {

    private final StorageObjectService objectService;
    private final StorageProperties properties;

    /**
     * 创建对象读取控制器。
     *
     * @param objectService 对象服务
     * @param properties 存储配置
     */
    public StorageObjectController(StorageObjectService objectService, StorageProperties properties) {
        this.objectService = objectService;
        this.properties = properties;
    }

    /**
     * 流式读取一个受管对象。
     *
     * @param authorization 内部授权头
     * @param rootName 受管根名称
     * @param path 根内相对路径
     * @return 对象内容
     * @throws IOException 无法打开已验证文件
     */
    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> content(
            @RequestHeader("Authorization") String authorization,
            @RequestParam String rootName,
            @RequestParam String path) throws IOException {
        requireToken(authorization);
        var object = objectService.requireReadable(rootName, path);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(object.size()).body(new InputStreamResource(Files.newInputStream(object.path())));
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

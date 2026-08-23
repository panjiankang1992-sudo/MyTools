package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.service.InternalAuthorizer;
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
import java.nio.file.Files;

/**
 * 内部受管对象流式读取接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/objects")
public class StorageObjectController {

    private final StorageObjectService objectService;
    private final InternalAuthorizer authorizer;

    /**
     * 创建对象读取控制器。
     *
     * @param objectService 对象服务
     * @param authorizer 内部鉴权器
     */
    public StorageObjectController(StorageObjectService objectService, InternalAuthorizer authorizer) {
        this.objectService = objectService;
        this.authorizer = authorizer;
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
        authorizer.require(authorization);
        var object = objectService.requireReadable(rootName, path);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(object.size()).body(new InputStreamResource(Files.newInputStream(object.path())));
    }

}

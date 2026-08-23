package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.model.AccessTicketView;
import com.yuyutian.mytools.storage.model.CreateAccessTicketRequest;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageAccessTicketService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * 访问票据创建、撤销和单次消费接口。
 */
@RestController
public class StorageAccessTicketController {
    private final StorageAccessTicketService ticketService;
    private final InternalAuthorizer authorizer;

    /**
     * 创建访问票据控制器。
     *
     * @param ticketService 票据服务
     * @param authorizer 内部鉴权器
     */
    public StorageAccessTicketController(StorageAccessTicketService ticketService, InternalAuthorizer authorizer) {
        this.ticketService = ticketService;
        this.authorizer = authorizer;
    }

    /**
     * 创建短期单用途票据。
     *
     * @param authorization 内部授权头
     * @param request 创建请求
     * @return 票据视图
     */
    @PostMapping("/api/internal/v1/storage/access-tickets")
    public ResponseEntity<AccessTicketView> create(@RequestHeader("Authorization") String authorization,
                                                   @Valid @RequestBody CreateAccessTicketRequest request) {
        authorizer.require(authorization);
        return ResponseEntity.accepted().body(ticketService.create(request));
    }

    /**
     * 撤销尚未消费的票据。
     *
     * @param id 票据标识
     * @param authorization 内部授权头
     * @return 空响应
     */
    @PostMapping("/api/internal/v1/storage/access-tickets/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID id,
                                       @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        ticketService.revoke(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 原子消费票据并下载对象。
     *
     * @param token 原始 Token
     * @return 对象流
     * @throws IOException 无法打开已验证对象
     */
    @GetMapping("/api/v1/storage/access/{token}")
    public ResponseEntity<InputStreamResource> consume(@PathVariable String token) throws IOException {
        var object = ticketService.consume(token);
        String fileName = object.path().getFileName().toString();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(object.size()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(Files.newInputStream(object.path())));
    }
}

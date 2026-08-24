package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateEbookImportRequest;
import com.yuyutian.mytools.reader.model.EbookCatalogView;
import com.yuyutian.mytools.reader.model.EbookImportView;
import com.yuyutian.mytools.reader.service.EbookImportService;
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
 * 书源电子书导入编排接口。
 */
@RestController
@RequestMapping("/api/v1/ebook-imports")
public class EbookImportController {

    private final EbookImportService importService;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建电子书导入控制器。
     *
     * @param importService 导入服务
     * @param authorizer 内部请求校验器
     */
    public EbookImportController(EbookImportService importService, InternalRequestAuthorizer authorizer) {
        this.importService = importService;
        this.authorizer = authorizer;
    }

    /**
     * 创建异步书源电子书导入。
     *
     * @param authorization 授权头
     * @param request 创建请求
     * @return 已受理导入
     */
    @PostMapping
    public ResponseEntity<EbookImportView> create(@RequestHeader("Authorization") String authorization,
                                                   @Valid @RequestBody CreateEbookImportRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(importService.create(request));
    }

    /**
     * 查询电子书导入状态。
     *
     * @param authorization 授权头
     * @param id 请求标识
     * @param ownerId 可选所有者标识
     * @return 导入视图
     */
    @GetMapping("/{id}")
    public EbookImportView get(@RequestHeader("Authorization") String authorization,
                               @PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ownerId == null ? importService.get(id) : importService.get(id, ownerId);
    }

    /**
     * 取消电子书导入。
     *
     * @param authorization 授权头
     * @param id 请求标识
     * @param ownerId 可选所有者标识
     * @return 导入视图
     */
    @PostMapping("/{id}/cancel")
    public EbookImportView cancel(@RequestHeader("Authorization") String authorization,
                                  @PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ownerId == null ? importService.cancel(id) : importService.cancel(id, ownerId);
    }

    /**
     * 查询已完成导入的电子书目录。
     *
     * @param authorization 授权头
     * @param id 导入请求标识
     * @param ownerId 可选所有者标识
     * @return 电子书目录
     */
    @GetMapping("/{id}/catalog")
    public EbookCatalogView catalog(@RequestHeader("Authorization") String authorization,
                                    @PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ownerId == null ? importService.catalog(id) : importService.catalog(id, ownerId);
    }
}

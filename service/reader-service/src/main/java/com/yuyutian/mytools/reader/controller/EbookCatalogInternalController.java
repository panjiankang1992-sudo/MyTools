package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CatalogBatchRequest;
import com.yuyutian.mytools.reader.model.CatalogBatchResult;
import com.yuyutian.mytools.reader.service.EbookCatalogWriteService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Executor 写入电子书目录的内部接口。
 */
@RestController
@RequestMapping("/api/internal/v1/ebook-imports")
public class EbookCatalogInternalController {

    private final EbookCatalogWriteService catalogWriteService;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建内部目录控制器。
     *
     * @param catalogWriteService 目录写入服务
     * @param authorizer 内部请求校验器
     */
    public EbookCatalogInternalController(EbookCatalogWriteService catalogWriteService,
                                          InternalRequestAuthorizer authorizer) {
        this.catalogWriteService = catalogWriteService;
        this.authorizer = authorizer;
    }

    /**
     * 保存目录批次。
     *
     * @param id 导入请求标识
     * @param authorization 内部授权头
     * @param request 目录批次
     * @return 保存摘要
     */
    @PostMapping("/{id}/catalog")
    public CatalogBatchResult save(@PathVariable UUID id,
                                   @RequestHeader("Authorization") String authorization,
                                   @Valid @RequestBody CatalogBatchRequest request) {
        authorizer.requireAuthorized(authorization);
        return catalogWriteService.save(id, request);
    }
}

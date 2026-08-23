package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateEbookImportRequest;
import com.yuyutian.mytools.reader.model.EbookImportView;
import com.yuyutian.mytools.reader.model.EbookCatalogView;
import com.yuyutian.mytools.reader.service.EbookImportService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 书源电子书导入编排接口。
 */
@RestController
@RequestMapping("/api/v1/ebook-imports")
public class EbookImportController {

    private final EbookImportService importService;

    /**
     * 创建电子书导入控制器。
     *
     * @param importService 导入服务
     */
    public EbookImportController(EbookImportService importService) {
        this.importService = importService;
    }

    /**
     * 创建异步书源电子书导入。
     *
     * @param request 创建请求
     * @return 已受理导入
     */
    @PostMapping
    public ResponseEntity<EbookImportView> create(@Valid @RequestBody CreateEbookImportRequest request) {
        return ResponseEntity.accepted().body(importService.create(request));
    }

    /**
     * 查询电子书导入状态。
     *
     * @param id 请求标识
     * @return 导入视图
     */
    @GetMapping("/{id}")
    public EbookImportView get(@PathVariable UUID id) {
        return importService.get(id);
    }

    /**
     * 取消电子书导入。
     *
     * @param id 请求标识
     * @return 导入视图
     */
    @PostMapping("/{id}/cancel")
    public EbookImportView cancel(@PathVariable UUID id) {
        return importService.cancel(id);
    }

    /**
     * 查询已完成导入的电子书目录。
     *
     * @param id 导入请求标识
     * @return 电子书目录
     */
    @GetMapping("/{id}/catalog")
    public EbookCatalogView catalog(@PathVariable UUID id) {
        return importService.catalog(id);
    }
}

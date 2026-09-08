package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.AudiobookExportInput;
import com.yuyutian.mytools.reader.model.AudiobookExportResult;
import com.yuyutian.mytools.reader.model.AudiobookExportView;
import com.yuyutian.mytools.reader.service.AudiobookGenerationService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 执行器读取和回写整书有声书导出归档的内部接口。
 */
@RestController
@RequestMapping("/api/internal/v1/audiobook-exports")
public class AudiobookExportInternalController {

    private final AudiobookGenerationService service;
    private final InternalRequestAuthorizer authorizer;

    /**
     * 创建有声书导出内部控制器。
     *
     * @param service 有声书生成服务
     * @param authorizer 内部请求授权校验器
     */
    public AudiobookExportInternalController(AudiobookGenerationService service,
                                             InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 返回执行器创建 ZIP 所需的受控章节清单。
     *
     * @param authorization 内部授权头
     * @param id 导出标识
     * @return 归档输入
     */
    @GetMapping("/{id}/input")
    public AudiobookExportInput input(@RequestHeader("Authorization") String authorization, @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.exportInput(id);
    }

    /**
     * 保存执行器已发布的完整 ZIP 归档结果。
     *
     * @param authorization 内部授权头
     * @param id 导出标识
     * @param result 已校验归档结果
     * @return 导出摘要
     */
    @PostMapping("/{id}/complete")
    public AudiobookExportView complete(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
                                        @Valid @RequestBody AudiobookExportResult result) {
        authorizer.requireAuthorized(authorization);
        return service.completeExport(id, result);
    }
}

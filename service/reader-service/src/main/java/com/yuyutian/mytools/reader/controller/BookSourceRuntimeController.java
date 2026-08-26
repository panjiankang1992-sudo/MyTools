package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.BookSourceRuntimeModels;
import com.yuyutian.mytools.reader.service.BookSourceRuntimeService;
import com.yuyutian.mytools.reader.service.InternalRequestAuthorizer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway 专用书源目录与正文接口。
 */
@RestController
@RequestMapping("/api/v1/source-runtime")
public class BookSourceRuntimeController {
    private final BookSourceRuntimeService service;
    private final InternalRequestAuthorizer authorizer;

    /** 创建书源执行控制器。 */
    public BookSourceRuntimeController(BookSourceRuntimeService service, InternalRequestAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /** 执行目录规则。 */
    @PostMapping("/catalog")
    public BookSourceRuntimeModels.Catalog catalog(@RequestHeader("Authorization") String authorization,
                                                    @Valid @RequestBody BookSourceRuntimeModels.CatalogRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.catalog(request);
    }

    /** 执行正文规则。 */
    @PostMapping("/content")
    public BookSourceRuntimeModels.Content content(@RequestHeader("Authorization") String authorization,
                                                    @Valid @RequestBody BookSourceRuntimeModels.ContentRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.content(request);
    }
}

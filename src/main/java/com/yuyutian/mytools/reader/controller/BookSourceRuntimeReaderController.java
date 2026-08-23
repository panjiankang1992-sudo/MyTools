package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import com.yuyutian.mytools.reader.service.BookSourceRuntimeReaderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App后端执行用户书源详情、目录与正文规则的接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/source-runtime")
@RequiredArgsConstructor
public class BookSourceRuntimeReaderController {
    private final BookSourceRuntimeReaderService service;

    /**
     * 加载图书详情和目录。
     *
     * @param userId 用户ID
     * @param request 目录请求
     * @return 图书目录
     */
    @PostMapping("/catalog")
    public Result<BookSourceRuntimeReaderModels.Catalog> catalog(@RequestAttribute("userId") Long userId,
                                                                  @Valid @RequestBody BookSourceRuntimeReaderModels.CatalogRequest request) {
        return Result.success(service.catalog(userId, request.sourceUrl(), request.bookUrl()));
    }

    /**
     * 加载章节正文。
     *
     * @param userId 用户ID
     * @param request 正文请求
     * @return 章节正文
     */
    @PostMapping("/content")
    public Result<BookSourceRuntimeReaderModels.Content> content(@RequestAttribute("userId") Long userId,
                                                                  @Valid @RequestBody BookSourceRuntimeReaderModels.ContentRequest request) {
        return Result.success(service.content(userId, request.sourceUrl(), request.chapterUrl(),
                request.chapterIndex()));
    }
}

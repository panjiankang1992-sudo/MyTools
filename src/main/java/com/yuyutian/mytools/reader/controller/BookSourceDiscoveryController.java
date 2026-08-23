package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.BookSourceDiscoveryModels;
import com.yuyutian.mytools.reader.service.BookSourceDiscoveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App通过后端工程化适配器导入书源的接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/source-discovery")
@RequiredArgsConstructor
public class BookSourceDiscoveryController {

    private final BookSourceDiscoveryService service;

    /**
     * 启动书源发现任务。
     *
     * @param userId 用户ID
     * @param request 网站请求
     * @return 异步任务
     */
    @PostMapping
    public Result<BookSourceDiscoveryModels.Task> start(@RequestAttribute("userId") Long userId,
                                                         @Valid @RequestBody BookSourceDiscoveryModels.StartRequest request) {
        return Result.success(service.start(userId, request.url()));
    }

    /**
     * 查询书源发现任务。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 异步任务
     */
    @GetMapping("/{taskId}")
    public Result<BookSourceDiscoveryModels.Task> find(@RequestAttribute("userId") Long userId,
                                                        @PathVariable String taskId) {
        return Result.success(service.find(userId, taskId));
    }
}

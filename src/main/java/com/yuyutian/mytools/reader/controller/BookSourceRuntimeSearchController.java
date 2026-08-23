package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeSearchModels;
import com.yuyutian.mytools.reader.service.BookSourceRuntimeSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App后端异步执行全部用户书源的搜索接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/source-search")
@RequiredArgsConstructor
public class BookSourceRuntimeSearchController {
    private final BookSourceRuntimeSearchService service;

    /**
     * 启动书源搜索任务。
     *
     * @param userId 用户ID
     * @param request 搜索请求
     * @return 任务快照
     */
    @PostMapping
    public Result<BookSourceRuntimeSearchModels.Task> start(@RequestAttribute("userId") Long userId,
                                                            @Valid @RequestBody BookSourceRuntimeSearchModels.StartRequest request) {
        return Result.success(service.start(userId, request.keyword(), request.page(), request.mode()));
    }

    /**
     * 查询书源搜索任务的增量结果。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param offset 已接收结果数量
     * @param limit 本次结果上限
     * @return 任务快照
     */
    @GetMapping("/{taskId}")
    public Result<BookSourceRuntimeSearchModels.Task> find(@RequestAttribute("userId") Long userId,
                                                           @PathVariable String taskId,
                                                           @RequestParam(defaultValue = "0") int offset,
                                                           @RequestParam(defaultValue = "100") int limit) {
        return Result.success(service.find(userId, taskId, offset, limit));
    }

    /**
     * 终止当前用户的书源搜索任务。
     *
     * @param userId 用户ID
     * @param taskId 任务ID
     * @return 终止后的任务快照
     */
    @DeleteMapping("/{taskId}")
    public Result<BookSourceRuntimeSearchModels.Task> cancel(@RequestAttribute("userId") Long userId,
                                                             @PathVariable String taskId) {
        return Result.success(service.cancel(userId, taskId));
    }
}

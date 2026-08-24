package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateSearchRequest;
import com.yuyutian.mytools.reader.model.SearchView;
import com.yuyutian.mytools.reader.service.ReaderSearchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * 书源搜索 HTTP 接口。
 */
@RestController
@RequestMapping("/api/v1/book-searches")
public class ReaderSearchController {

    private final ReaderSearchService searchService;

    /**
     * 创建书源搜索控制器。
     *
     * @param searchService 搜索服务
     */
    public ReaderSearchController(ReaderSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 创建异步书源搜索。
     *
     * @param request 搜索请求
     * @return 已受理的搜索视图
     */
    @PostMapping
    public ResponseEntity<SearchView> create(@Valid @RequestBody CreateSearchRequest request) {
        return ResponseEntity.accepted().body(searchService.create(request));
    }

    /**
     * 查询搜索状态并聚合最新分片结果。
     *
     * @param id 搜索请求标识
     * @return 搜索视图
     */
    @GetMapping("/{id}")
    public SearchView get(@PathVariable UUID id,@RequestParam(required=false)Long ownerId) {
        return ownerId==null?searchService.get(id):searchService.get(id,ownerId);
    }

    /**
     * 取消搜索任务。
     *
     * @param id 搜索请求标识
     * @return 搜索视图
     */
    @PostMapping("/{id}/cancel")
    public SearchView cancel(@PathVariable UUID id,@RequestParam(required=false)Long ownerId) {
        return ownerId==null?searchService.cancel(id):searchService.cancel(id,ownerId);
    }
}

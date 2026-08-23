package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.ReadingProgress;
import com.yuyutian.mytools.reader.model.ReadingProgressSyncResponse;
import com.yuyutian.mytools.reader.model.SaveReadingProgressRequest;
import com.yuyutian.mytools.reader.service.ReadingProgressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HarmonyOS客户端阅读进度接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/progress")
@RequiredArgsConstructor
public class ReadingProgressController {
    private final ReadingProgressService service;

    /**
     * 获取当前用户全部阅读进度。
     *
     * @param request HTTP请求
     * @return 阅读进度列表
     */
    @GetMapping
    public ResponseEntity<Result<List<ReadingProgress>>> list(HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.list(requireUserId(request))));
    }

    /**
     * 获取单本远程图书的阅读进度。
     *
     * @param bookId 图书稳定哈希
     * @param request HTTP请求
     * @return 阅读进度，不存在时数据为空
     */
    @GetMapping("/{bookId}")
    public ResponseEntity<Result<ReadingProgress>> find(@PathVariable String bookId, HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.find(requireUserId(request), bookId)));
    }

    /**
     * 保存一本书的阅读进度。
     *
     * @param body 保存请求
     * @param request HTTP请求
     * @return 同步结果
     */
    @PutMapping
    public ResponseEntity<Result<ReadingProgressSyncResponse>> save(
            @Valid @RequestBody SaveReadingProgressRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.save(requireUserId(request), body)));
    }

    private Long requireUserId(HttpServletRequest request) {
        Object value = request.getAttribute("userId");
        if (value instanceof Long userId) {
            return userId;
        }
        throw new BusinessException(ErrorCode.AUTH_002);
    }
}

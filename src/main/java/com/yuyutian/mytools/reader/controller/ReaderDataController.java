package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.ReaderDataDeleteResponse;
import com.yuyutian.mytools.reader.model.ReaderDataSummary;
import com.yuyutian.mytools.reader.service.ReaderDataService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户阅读同步数据生命周期接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/data")
@RequiredArgsConstructor
public class ReaderDataController {
    private final ReaderDataService service;

    /**
     * 获取云端阅读数据摘要。
     *
     * @param request HTTP请求
     * @return 数据数量摘要
     */
    @GetMapping("/summary")
    public ResponseEntity<Result<ReaderDataSummary>> summary(HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.summary(requireUserId(request))));
    }

    /**
     * 删除当前用户全部云端阅读同步数据。
     *
     * @param request HTTP请求
     * @return 删除记录总数
     */
    @DeleteMapping
    public ResponseEntity<Result<ReaderDataDeleteResponse>> deleteAll(HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.deleteAll(requireUserId(request))));
    }

    private Long requireUserId(HttpServletRequest request) {
        Object value = request.getAttribute("userId");
        if (value instanceof Long userId) return userId;
        throw new BusinessException(ErrorCode.AUTH_002);
    }
}

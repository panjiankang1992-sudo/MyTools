package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.BookSourceSyncResponse;
import com.yuyutian.mytools.reader.model.SaveBookSourceRequest;
import com.yuyutian.mytools.reader.model.SyncedBookSource;
import com.yuyutian.mytools.reader.service.BookSourceSyncService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HarmonyOS客户端书源同步接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/sources")
@RequiredArgsConstructor
public class BookSourceSyncController {
    private final BookSourceSyncService service;

    /** 获取用户书源。 @param request HTTP请求 @return 书源列表 */
    @GetMapping
    public ResponseEntity<Result<List<SyncedBookSource>>> list(HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.list(requireUserId(request))));
    }

    /** 保存书源或墓碑。 @param body 保存请求 @param request HTTP请求 @return 同步结果 */
    @PutMapping
    public ResponseEntity<Result<BookSourceSyncResponse>> save(
            @Valid @RequestBody SaveBookSourceRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.save(requireUserId(request), body)));
    }

    private Long requireUserId(HttpServletRequest request) {
        Object value = request.getAttribute("userId");
        if (value instanceof Long userId) return userId;
        throw new BusinessException(ErrorCode.AUTH_002);
    }
}

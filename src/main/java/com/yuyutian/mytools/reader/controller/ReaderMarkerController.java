package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.ReaderMarker;
import com.yuyutian.mytools.reader.model.ReaderMarkerSyncResponse;
import com.yuyutian.mytools.reader.model.SaveReaderMarkerRequest;
import com.yuyutian.mytools.reader.service.ReaderMarkerService;
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
 * HarmonyOS客户端书签与批注同步接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/markers")
@RequiredArgsConstructor
public class ReaderMarkerController {
    private final ReaderMarkerService service;

    /**
     * 获取当前用户的书签、批注和删除墓碑。
     *
     * @param request HTTP请求
     * @return 阅读标记列表
     */
    @GetMapping
    public ResponseEntity<Result<List<ReaderMarker>>> list(HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.list(requireUserId(request))));
    }

    /**
     * 保存书签、批注或删除墓碑。
     *
     * @param body 保存请求
     * @param request HTTP请求
     * @return 同步结果
     */
    @PutMapping
    public ResponseEntity<Result<ReaderMarkerSyncResponse>> save(
            @Valid @RequestBody SaveReaderMarkerRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.save(requireUserId(request), body)));
    }

    private Long requireUserId(HttpServletRequest request) {
        Object value = request.getAttribute("userId");
        if (value instanceof Long userId) return userId;
        throw new BusinessException(ErrorCode.AUTH_002);
    }
}

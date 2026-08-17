package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.reader.model.SaveShelfBookRequest;
import com.yuyutian.mytools.reader.model.ShelfBook;
import com.yuyutian.mytools.reader.model.ShelfBookSyncResponse;
import com.yuyutian.mytools.reader.service.ShelfBookService;
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
 * HarmonyOS客户端书架同步接口。
 */
@RestController
@RequestMapping("/api/app/v1/reader/shelf")
@RequiredArgsConstructor
public class ShelfBookController {
    private final ShelfBookService service;

    /**
     * 获取当前用户的书架记录。
     *
     * @param request HTTP请求
     * @return 书架及墓碑
     */
    @GetMapping
    public ResponseEntity<Result<List<ShelfBook>>> list(HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.list(requireUserId(request))));
    }

    /**
     * 保存一条书架记录或删除墓碑。
     *
     * @param body 保存请求
     * @param request HTTP请求
     * @return 同步结果
     */
    @PutMapping
    public ResponseEntity<Result<ShelfBookSyncResponse>> save(
            @Valid @RequestBody SaveShelfBookRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(Result.success(service.save(requireUserId(request), body)));
    }

    private Long requireUserId(HttpServletRequest request) {
        Object value = request.getAttribute("userId");
        if (value instanceof Long userId) return userId;
        throw new BusinessException(ErrorCode.AUTH_002);
    }
}

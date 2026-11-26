package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 拦截框架校验错误，避免默认日志输出被拒绝的地址、密钥和正文值。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=OutboundController.class)
public class OutboundInputExceptionHandler {
    /** 只返回固定字段错误，不返回 rejectedValue 或解析异常详情。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,String>> invalidInput() {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store, private")
                .body(Map.of("code", ErrorCode.OUTBOUND_INVALID.code(), "message", ErrorCode.OUTBOUND_INVALID.message()));
    }
}

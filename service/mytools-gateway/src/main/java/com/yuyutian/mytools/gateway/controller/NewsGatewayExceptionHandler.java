package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.NewsGatewayRejectedException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 保留新闻幂等冲突等明确拒绝状态，兼容现有响应信封。 */
@Order(-1)
@RestControllerAdvice(assignableTypes = NewsGatewayController.class)
public class NewsGatewayExceptionHandler {
    /** 返回稳定业务错误，不输出下游请求正文。 */
    @ExceptionHandler(NewsGatewayRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(NewsGatewayRejectedException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("code", exception.code().name(), "message", exception.code().name()));
    }
}

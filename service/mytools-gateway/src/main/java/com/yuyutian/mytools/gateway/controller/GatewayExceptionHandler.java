package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Gateway 稳定错误响应转换器。
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    /**
     * 转换认证失败异常。
     */
    @ExceptionHandler(GatewayUnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> unauthorized(GatewayUnauthorizedException exception) {
        return Map.of("code", "GATEWAY_001", "message", "Gateway authentication failed");
    }

    /**
     * 转换灰度路由未启用异常。
     */
    @ExceptionHandler(GatewayRouteDisabledException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> routeDisabled(GatewayRouteDisabledException exception) {
        return Map.of("code", "GATEWAY_002", "message", "Gateway route is not enabled");
    }
}

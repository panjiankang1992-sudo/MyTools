package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayBadRequestException;
import com.yuyutian.mytools.gateway.service.GatewayDownstreamException;
import com.yuyutian.mytools.gateway.service.GatewayNotFoundException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    /**
     * 转换稳定契约请求错误。
     */
    @ExceptionHandler({GatewayBadRequestException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(Exception exception) {
        return Map.of("code", "GATEWAY_003", "message", "Gateway request is invalid");
    }

    /**
     * 转换下游不可用错误。
     */
    @ExceptionHandler(GatewayDownstreamException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> downstream(GatewayDownstreamException exception) {
        return Map.of("code", "GATEWAY_004", "message", "Gateway downstream is unavailable");
    }

    /**
     * 转换租户资源不存在错误。
     */
    @ExceptionHandler(GatewayNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(GatewayNotFoundException exception) {
        return Map.of("code", "GATEWAY_005", "message", "Gateway resource was not found");
    }
}

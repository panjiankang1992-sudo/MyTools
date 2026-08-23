package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.service.DeliveryNotFoundException;
import com.yuyutian.mytools.messaging.service.DeliveryStateInvalidException;
import com.yuyutian.mytools.messaging.service.DeliveryInvalidException;
import com.yuyutian.mytools.messaging.service.ProviderNotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 消息服务业务异常响应转换器。
 */
@RestControllerAdvice
public class MessagingExceptionHandler {

    /**
     * 转换投递不存在异常。
     */
    @ExceptionHandler(DeliveryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleDeliveryNotFound(DeliveryNotFoundException exception) {
        return Map.of("code", ErrorCode.DELIVERY_NOT_FOUND.code(), "message", ErrorCode.DELIVERY_NOT_FOUND.message());
    }

    /**
     * 转换 provider 未配置异常。
     */
    @ExceptionHandler(ProviderNotConfiguredException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleProviderNotConfigured(ProviderNotConfiguredException exception) {
        return Map.of("code", ErrorCode.PROVIDER_NOT_CONFIGURED.code(),
                "message", ErrorCode.PROVIDER_NOT_CONFIGURED.message());
    }

    /**
     * 转换投递状态冲突异常。
     */
    @ExceptionHandler(DeliveryStateInvalidException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleDeliveryStateInvalid(DeliveryStateInvalidException exception) {
        return Map.of("code", ErrorCode.DELIVERY_STATE_INVALID.code(),
                "message", ErrorCode.DELIVERY_STATE_INVALID.message());
    }

    /**
     * 转换投递请求无效异常。
     */
    @ExceptionHandler(DeliveryInvalidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleDeliveryInvalid(DeliveryInvalidException exception) {
        return Map.of("code", ErrorCode.DELIVERY_INVALID.code(),
                "message", ErrorCode.DELIVERY_INVALID.message());
    }
}

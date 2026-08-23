package com.yuyutian.mytools.automation.controller;

import com.yuyutian.mytools.automation.model.ErrorCode;
import com.yuyutian.mytools.automation.service.AutomationRunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 消息自动化业务异常响应转换器。
 */
@RestControllerAdvice
public class AutomationExceptionHandler {

    /**
     * 转换自动化运行不存在异常。
     */
    @ExceptionHandler(AutomationRunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleRunNotFound(AutomationRunNotFoundException exception) {
        return Map.of("code", ErrorCode.RUN_NOT_FOUND.code(), "message", ErrorCode.RUN_NOT_FOUND.message());
    }
}

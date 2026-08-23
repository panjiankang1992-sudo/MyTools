package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.SearchNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 阅读服务异常响应转换器。
 */
@RestControllerAdvice
public class ReaderExceptionHandler {

    /**
     * 转换搜索请求不存在异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(SearchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(SearchNotFoundException exception) {
        return Map.of("code", ErrorCode.SEARCH_NOT_FOUND.code(),
                "message", ErrorCode.SEARCH_NOT_FOUND.message());
    }
}

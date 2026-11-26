package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.service.NewsSchedulerClient;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 对外只返回稳定错误码，不回显请求或数据库内容。 */
@RestControllerAdvice
public class NewsExceptionHandler {
    /** 限流响应提供明确的重试时刻，不进行隐式睡眠或自动重试。 */
    @ExceptionHandler(SourceRateLimitException.class)
    public ResponseEntity<Failure> limited(SourceRateLimitException exception) {
        ErrorCode code = exception.code();
        return ResponseEntity.status(code.status()).header("Retry-After",
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(exception.retryAt().atZone(java.time.ZoneOffset.UTC)))
                .body(new Failure(code.name(), code.message()));
    }
    /** 转换业务错误。 */
    @ExceptionHandler(NewsException.class)
    public ResponseEntity<Failure> business(NewsException exception) { return response(exception.code()); }

    /** 工作租约校验失败与暂时不可用均返回稳定错误，不输出远端载荷。 */
    @ExceptionHandler(NewsSchedulerClient.Failure.class)
    public ResponseEntity<Failure> scheduler(NewsSchedulerClient.Failure exception) {
        return response(exception.permanent() ? ErrorCode.NEWS_016 : ErrorCode.NEWS_015);
    }

    /** 转换参数校验错误。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, org.springframework.web.bind.MissingRequestHeaderException.class})
    public ResponseEntity<Failure> invalid() { return response(ErrorCode.NEWS_002); }

    /** 隐藏数据库及运行时异常的敏感信息。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> unexpected() { return response(ErrorCode.NEWS_009); }

    private ResponseEntity<Failure> response(ErrorCode code) {
        return ResponseEntity.status(code.status()).body(new Failure(code.name(), code.message()));
    }

    /** 可公开的错误结构。 */
    public record Failure(String code, String message) { }
}

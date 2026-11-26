package com.yuyutian.mytools.rss.controller;

import com.yuyutian.mytools.feed.FeedException;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import com.yuyutian.mytools.rss.service.FeedAccessService;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 返回稳定错误信封，隐藏 URL、SQL 和原始异常信息。 */
@RestControllerAdvice
public class RssExceptionHandler {
    /** 映射领域错误。 */
    @ExceptionHandler(RssException.class)
    public ResponseEntity<Map<String, String>> business(RssException exception) { return error(exception.code()); }
    /** 映射安全解析或抓取错误。 */
    @ExceptionHandler(FeedException.class)
    public ResponseEntity<Map<String, String>> feed(FeedException exception) { return error(FeedAccessService.code(exception)); }
    /** 拒绝无效请求参数，保留统一业务码。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception exception) { return error(ErrorCode.RSS_001); }
    /** 唯一键冲突不泄漏数据库约束内容。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException exception) { return error(ErrorCode.RSS_009); }
    /** 未知服务异常不得作为成功响应或带堆栈返回。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception exception) { return error(ErrorCode.RSS_011); }
    private ResponseEntity<Map<String, String>> error(ErrorCode code) { return ResponseEntity.status(code.status()).body(Map.of("code", code.name(), "message", code.name())); }
}

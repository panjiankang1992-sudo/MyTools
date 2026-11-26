package com.yuyutian.mytools.toolbox.common;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把所有失败统一成错误信封，details 只保留字段路径、限额与索引。
 * 未预期异常只记录服务端日志，不向客户端泄漏堆栈、SQL、Cookie 或内部地址。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理显式业务异常。
     *
     * @param exception 业务异常
     * @return 错误信封响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException exception) {
        return ResponseEntity.status(exception.errorCode().httpStatus()).body(ApiErrorResponse.of(exception));
    }

    /**
     * 处理 JSON 解析失败，未知字段与类型错误都归入参数无效。
     *
     * @param exception 解析异常
     * @return 错误信封响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        String field = null;
        String reason = "malformedJson";
        Throwable cause = exception.getCause();
        if (cause instanceof UnrecognizedPropertyException unrecognized) {
            field = unrecognized.getPropertyName();
            reason = "unknownField";
        } else if (cause instanceof InvalidFormatException invalidFormat) {
            field = lastFieldName(invalidFormat.getPath());
            reason = "invalidType";
        } else if (cause instanceof JsonMappingException mapping) {
            field = lastFieldName(mapping.getPath());
            reason = "invalidValue";
        }
        return invalid(field, reason, null);
    }

    /**
     * 处理查询参数类型不匹配。
     *
     * @param exception 类型不匹配异常
     * @return 错误信封响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return invalid(exception.getName(), "invalidType", null);
    }

    /**
     * 处理必须参数缺失。
     *
     * @param exception 参数缺失异常
     * @return 错误信封响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception) {
        return invalid(exception.getParameterName(), "missing", null);
    }

    /**
     * 处理不支持的请求媒体类型。
     *
     * @param exception 媒体类型不支持异常
     * @return 错误信封响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException exception) {
        return invalid(null, "unsupportedContentType", null);
    }

    /**
     * 处理不可接受的响应媒体类型。
     *
     * @param exception 响应媒体类型不可接受异常
     * @return 错误信封响应
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception) {
        return invalid(null, "notAcceptable", null);
    }

    /**
     * 处理不存在的路由，统一按资源不存在返回，不区分内部路由是否存在。
     *
     * @param exception 路由不存在异常
     * @return 错误信封响应
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException exception) {
        return fail(ErrorCode.TOOLBOX_003, Map.of());
    }

    /**
     * 处理静态资源不存在的请求，与路由不存在保持一致。
     *
     * @param exception 资源不存在异常
     * @return 错误信封响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception) {
        return fail(ErrorCode.TOOLBOX_003, Map.of());
    }

    /**
     * 处理方法不允许，按资源不存在返回以避免探测路由。
     *
     * @param exception 方法不支持异常
     * @return 错误信封响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return fail(ErrorCode.TOOLBOX_003, Map.of());
    }

    /**
     * 兜底处理未预期异常：服务端记录堆栈，客户端只得到稳定错误码。
     *
     * @param exception 未预期异常
     * @param request   当前请求
     * @return 错误信封响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOG.error("unexpected request failure, path={}", request.getRequestURI(), exception);
        return fail(ErrorCode.TOOLBOX_008, Map.of());
    }

    /**
     * 构造参数无效的错误响应。
     *
     * @param field  字段路径，可为空
     * @param reason 机器可读原因
     * @param extras 附加安全键值，可为空
     * @return 错误信封响应
     */
    private ResponseEntity<ApiErrorResponse> invalid(String field, String reason, Map<String, Object> extras) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (field != null) {
            details.put("field", field);
        }
        details.put("reason", reason);
        if (extras != null) {
            details.putAll(extras);
        }
        return fail(ErrorCode.TOOLBOX_001, details);
    }

    /**
     * 按错误码构造错误响应。
     *
     * @param code    稳定错误码
     * @param details 安全 details
     * @return 错误信封响应
     */
    private ResponseEntity<ApiErrorResponse> fail(ErrorCode code, Map<String, Object> details) {
        return ResponseEntity.status(code.httpStatus()).body(ApiErrorResponse.of(new BusinessException(code, details)));
    }

    /**
     * 取 JSON 路径中最后一个字段名，用于 details，不含具体取值。
     *
     * @param path Jackson 字段路径
     * @return 最后一段字段名或 null
     */
    private String lastFieldName(List<JsonMappingException.Reference> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return path.get(path.size() - 1).getFieldName();
    }
}

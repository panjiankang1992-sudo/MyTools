package com.yuyutian.mytools.gateway.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/**
 * 将面向客户端的成功响应统一包装为 App 使用的稳定信封。
 */
@ControllerAdvice(basePackages = "com.yuyutian.mytools.gateway.controller")
public class GatewayEnvelopeAdvice implements ResponseBodyAdvice<Object> {
    /**
     * 所有 Gateway 控制器响应都使用统一信封。
     *
     * @param returnType 返回类型
     * @param converterType 转换器类型
     * @return 是否包装
     */
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * 包装成功响应，同时避免重复包装显式错误信封。
     *
     * @param body 原响应
     * @param returnType 返回类型
     * @param selectedContentType 内容类型
     * @param selectedConverterType 转换器类型
     * @param request 请求
     * @param response 响应
     * @return 稳定响应信封
     */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Map<?, ?> map && map.containsKey("code")) {
            return body;
        }
        return Map.of("code", "0000", "message", "OK", "data", body == null ? Map.of() : body);
    }
}

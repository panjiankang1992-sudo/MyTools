package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.service.DeliveryNotFoundException;
import com.yuyutian.mytools.messaging.service.DeliveryStateInvalidException;
import com.yuyutian.mytools.messaging.service.DeliveryInvalidException;
import com.yuyutian.mytools.messaging.service.ProviderNotConfiguredException;
import com.yuyutian.mytools.messaging.service.InboundMessageNotFoundException;
import com.yuyutian.mytools.messaging.service.OneBotIngressDisabledException;
import com.yuyutian.mytools.messaging.service.OneBotPayloadInvalidException;
import com.yuyutian.mytools.messaging.service.AttachmentDownloadInvalidException;
import com.yuyutian.mytools.messaging.service.AttachmentDownloadNotFoundException;
import com.yuyutian.mytools.messaging.service.EmailIngressDisabledException;
import com.yuyutian.mytools.messaging.service.EmailIngressException;
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

    /**
     * 转换入站消息不存在异常。
     */
    @ExceptionHandler(InboundMessageNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleInboundMessageNotFound(InboundMessageNotFoundException exception) {
        return Map.of("code", ErrorCode.INBOUND_NOT_FOUND.code(),
                "message", ErrorCode.INBOUND_NOT_FOUND.message());
    }

    /**
     * 转换 OneBot 入站适配器未启用异常。
     */
    @ExceptionHandler(OneBotIngressDisabledException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleOneBotIngressDisabled(OneBotIngressDisabledException exception) {
        return Map.of("code", ErrorCode.ONEBOT_INGRESS_DISABLED.code(),
                "message", ErrorCode.ONEBOT_INGRESS_DISABLED.message());
    }

    /**
     * 转换 OneBot 负载无效异常。
     */
    @ExceptionHandler(OneBotPayloadInvalidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleOneBotPayloadInvalid(OneBotPayloadInvalidException exception) {
        return Map.of("code", ErrorCode.ONEBOT_PAYLOAD_INVALID.code(),
                "message", ErrorCode.ONEBOT_PAYLOAD_INVALID.message());
    }

    /**
     * 转换附件下载不存在异常。
     */
    @ExceptionHandler(AttachmentDownloadNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleAttachmentDownloadNotFound(AttachmentDownloadNotFoundException exception) {
        return Map.of("code", ErrorCode.ATTACHMENT_DOWNLOAD_NOT_FOUND.code(),
                "message", ErrorCode.ATTACHMENT_DOWNLOAD_NOT_FOUND.message());
    }

    /**
     * 转换不可下载附件异常。
     */
    @ExceptionHandler(AttachmentDownloadInvalidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleAttachmentDownloadInvalid(AttachmentDownloadInvalidException exception) {
        return Map.of("code", ErrorCode.ATTACHMENT_DOWNLOAD_INVALID.code(),
                "message", ErrorCode.ATTACHMENT_DOWNLOAD_INVALID.message());
    }

    /**
     * 转换邮件入站未启用异常。
     */
    @ExceptionHandler(EmailIngressDisabledException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleEmailIngressDisabled(EmailIngressDisabledException exception) {
        return Map.of("code", ErrorCode.EMAIL_INGRESS_DISABLED.code(),
                "message", ErrorCode.EMAIL_INGRESS_DISABLED.message());
    }

    /**
     * 转换邮件入站执行失败异常。
     */
    @ExceptionHandler(EmailIngressException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handleEmailIngressFailed(EmailIngressException exception) {
        return Map.of("code", ErrorCode.EMAIL_INGRESS_FAILED.code(),
                "message", ErrorCode.EMAIL_INGRESS_FAILED.message());
    }
}

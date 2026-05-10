package com.yuyutian.mytools.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Message helper for i18n support.
 */
@Component
public class MessageHelper {

    private static MessageSource messageSource;

    @Autowired
    public void setMessageSource(MessageSource messageSource) {
        MessageHelper.messageSource = messageSource;
    }

    /**
     * Get internationalized message by key.
     */
    public static String getMessage(String messageKey) {
        try {
            return messageSource.getMessage(messageKey, null, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return messageKey;
        }
    }

    /**
     * Get internationalized message with arguments.
     */
    public static String getMessage(String messageKey, Object... args) {
        try {
            return messageSource.getMessage(messageKey, args, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return messageKey;
        }
    }
}

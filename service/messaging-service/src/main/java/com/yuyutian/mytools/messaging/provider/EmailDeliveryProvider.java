package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.DeliveryRecord;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * SMTP 邮件原子投递适配器。
 */
@Component
public class EmailDeliveryProvider implements DeliveryProvider {

    private final JavaMailSender mailSender;
    private final MessagingProperties properties;

    /**
     * 创建邮件投递适配器。
     */
    public EmailDeliveryProvider(JavaMailSender mailSender, MessagingProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * 返回邮件渠道类型。
     */
    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }

    /**
     * 使用服务端 SMTP 密钥发送纯文本邮件。
     */
    @Override
    public String deliver(DeliveryRecord delivery) {
        if (properties.mailFrom() == null || properties.mailFrom().isBlank()) {
            throw new IllegalStateException("Mail sender is not configured");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mailFrom());
            helper.setTo(delivery.recipient());
            helper.setSubject(delivery.subject() == null ? "" : delivery.subject());
            helper.setText(delivery.body(), false);
            String messageId = "<" + delivery.id() + "@mytools.messaging>";
            message.setHeader("Message-ID", messageId);
            mailSender.send(message);
            return messageId;
        } catch (MessagingException exception) {
            throw new IllegalStateException("Mail message cannot be constructed", exception);
        }
    }
}

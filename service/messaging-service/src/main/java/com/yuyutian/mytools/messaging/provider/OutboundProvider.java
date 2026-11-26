package com.yuyutian.mytools.messaging.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.utils.outbound.LimitedResponseBody;
import com.yuyutian.mytools.messaging.service.OutboundRateLimiter;
import com.yuyutian.mytools.messaging.service.OutboundException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 提供方接受并不等于最终到达收件人，所有不确定结果保留 UNKNOWN。 */
@Component
public class OutboundProvider {
    private final JavaMailSender sender;
    private final MessagingProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final OutboundRateLimiter limiter;
    private final Clock clock;
    /** 创建无自动重试、无跳转的渠道适配器。 */
    @org.springframework.beans.factory.annotation.Autowired
    public OutboundProvider(JavaMailSender sender, MessagingProperties properties, ObjectMapper json, OutboundRateLimiter limiter) {
        this(sender,properties,json,limiter,Clock.systemUTC());
    }
    /** 注入时钟以验证准备载荷和等待配额后的截止边界。 */
    public OutboundProvider(JavaMailSender sender,MessagingProperties properties,ObjectMapper json,OutboundRateLimiter limiter,Clock clock) {
        this.sender = sender; this.properties = properties; this.json = json;
        this.clock = clock;
        this.limiter = limiter;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** 为独立渠道协议测试提供不访问共享数据库的构造方式。 */
    public OutboundProvider(JavaMailSender sender, MessagingProperties properties, ObjectMapper json) { this(sender,properties,json,null); }
    /** 根据已经验证的目的地发送一份冻结内容。 */
    public Result send(Delivery delivery, Secret secret) {
        if(contentExpired(delivery))return expired();
        return switch (delivery.channel()) {
            case EMAIL -> email(delivery, secret);
            case FEISHU -> feishu(delivery, secret);
            case QQ -> new Result("FAILED", null, "MESSAGING_021");
        };
    }
    private Result email(Delivery d, Secret secret) {
        MimeMessage message;
        String reference = "<" + d.id() + "." + d.generation() + "@mytools.messaging>";
        try {
            if (properties.mailFrom() == null || properties.mailFrom().isBlank()) return rejected();
            message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, d.content().html() != null && !d.content().html().isBlank(), StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mailFrom());
            helper.setTo(secret.address());
            helper.setSubject(d.content().subject());
            if (d.content().html() == null || d.content().html().isBlank()) {
                helper.setText(d.content().text(), false);
            } else {
                // 同一 MIME 邮件携带纯文本和 HTML 替代内容。
                helper.setText(d.content().text(), d.content().html());
            }
            message.saveChanges();
            message.setHeader("Message-ID", reference);
        } catch (MessagingException | RuntimeException exception) { return rejected(); }
        try {
            // MIME 构造也可能跨过截止时间，实际调用前再次确认。
            if(contentExpired(d))return expired();
            sender.send(message);
            return accepted(reference);
        } catch (MailAuthenticationException exception) { return rejected(); }
        catch (RuntimeException exception) { return unknown(); }
    }
    private Result feishu(Delivery d, Secret secret) {
        var bodyReader = new LimitedResponseBody();
        try {
            byte[] body = feishuPayload(d.content(), secret.signingSecret(), Instant.now().getEpochSecond());
            if (body.length > 16384) return rejected();
            if (limiter != null) {
                // 在数据库事务之外等待启动配额，不占用连接或把未知请求重新发送。
                try {Thread.sleep(limiter.reserve(secret.address(),Instant.now()));}
                catch(OutboundException exception){return rejected();}
            }
            var request = HttpRequest.newBuilder(URI.create(secret.address())).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            // 渠道配额等待后可能到期，禁止使用此前载入内存的正文开始发送。
            if(contentExpired(d))return expired();
            var response = http.sendAsync(request, info -> bodyReader)
                    .get(25, java.util.concurrent.TimeUnit.SECONDS);
                byte[] bytes = response.body();
                if (response.statusCode() >= 400 && response.statusCode() < 500) return rejected();
                if (response.statusCode() != 200) return unknown();
                var result = json.readTree(bytes);
                // 不能把 HTTP 200 或缺失 code 的响应当成发送成功。
                if (result == null || !result.path("code").isIntegralNumber()) return unknown();
                return result.path("code").asInt(-1) == 0 ? accepted(null) : rejected();
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return unknown(); }
        catch (Exception exception) { return unknown(); }
        finally { bodyReader.cancel(); }
    }
    /** 使用官方 HMAC 空消息签名规则，并禁止正文触发 at 标签。 */
    public byte[] feishuPayload(Content content, String secret, long timestamp) throws java.security.GeneralSecurityException, com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "text");
        body.put("content", Map.of("text", (content.subject() + "\n\n" + content.text()).replace('<', '\u2039').replace('>', '\u203a')));
        if (secret != null && !secret.isBlank()) {
            // 签名串作为 HMAC 密钥，消息体为空字节数组。
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            body.put("timestamp", Long.toString(timestamp));
            body.put("sign", Base64.getEncoder().encodeToString(mac.doFinal(new byte[0])));
        }
        return json.writeValueAsBytes(body);
    }
    private boolean contentExpired(Delivery delivery){return delivery.contentExpiresAt()!=null && !delivery.contentExpiresAt().isAfter(clock.instant());}
    private Result expired(){return new Result("EXPIRED",null,com.yuyutian.mytools.messaging.model.ErrorCode.OUTBOUND_CONTENT_EXPIRED.code());}
    private Result accepted(String reference) { return new Result("PROVIDER_ACCEPTED", reference, null); }
    private Result rejected() { return new Result("FAILED", null, "MESSAGING_022"); }
    private Result unknown() { return new Result("UNKNOWN", null, "MESSAGING_023"); }
    /** 提供方结果仅使用稳定错误码。 */
    public record Result(String status, String reference, String errorCode) { }
}

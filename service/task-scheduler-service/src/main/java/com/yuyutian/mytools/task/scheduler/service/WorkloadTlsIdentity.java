package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.WorkloadAuthorizationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/** 只读取 TLS 容器验证后的客户端证书，忽略所有转发证书及主体 Header。 */
@Component
public class WorkloadTlsIdentity {
    private final WorkloadAuthorizationProperties properties;

    /** 注入精确 SAN 与节点名称的可信映射。 */
    public WorkloadTlsIdentity(WorkloadAuthorizationProperties properties) {
        this.properties = properties;
    }

    /** 受信执行器必须匹配其已注册节点名称，不能仅凭另一个节点的 UUID 领取。 */
    public Identity executor(String nodeName) {
        Identity identity = certificate();
        if (!identity.uri().equals(properties.executorIdentities().get(nodeName))) {
            throw unauthorized();
        }
        return identity;
    }

    /** 仅 Reader 工作负载允许查询在线授权和公钥。 */
    public Identity reader() {
        Identity identity = certificate();
        if (!properties.readerIdentities().contains(identity.uri())) {
            throw unauthorized();
        }
        return identity;
    }

    private Identity certificate() {
        if (!properties.enabled() || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || !attributes.getRequest().isSecure()
                || !(attributes.getRequest().getAttribute("jakarta.servlet.request.X509Certificate") instanceof X509Certificate[] chain)
                || chain.length == 0 || chain.length > 8) {
            throw unauthorized();
        }
        try {
            X509Certificate leaf = chain[0];
            leaf.checkValidity(new Date());
            if (leaf.getBasicConstraints() >= 0 || leaf.getExtendedKeyUsage() == null
                    || !leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")) {
                throw unauthorized();
            }
            var alternatives = leaf.getSubjectAlternativeNames();
            List<String> identities = new ArrayList<>();
            if (alternatives != null) {
                for (List<?> alternative : alternatives) {
                    if (alternative.size() == 2 && Integer.valueOf(6).equals(alternative.getFirst())
                            && alternative.get(1) instanceof String value) {
                        identities.add(value);
                    }
                }
            }
            if (identities.size() != 1) {
                throw unauthorized();
            }
            String thumbprint = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
            return new Identity(identities.getFirst(), thumbprint);
        } catch (Exception exception) {
            // 证书内容及外部异常链不进入响应或日志。
            throw unauthorized();
        }
    }

    private static SchedulerException unauthorized() {
        return new SchedulerException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Trusted workload TLS identity is required");
    }

    /** 证书主体与 DER 指纹只在执行授权中使用，不表示业务 owner。 */
    public record Identity(String uri, String thumbprint) {
    }
}

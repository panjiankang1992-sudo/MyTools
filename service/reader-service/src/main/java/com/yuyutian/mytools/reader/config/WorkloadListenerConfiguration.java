package com.yuyutian.mytools.reader.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** 在原有回环 HTTP 之外增加专用双向 TLS 端口，不接受代理转发的证书身份。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "workload.listener.enabled", havingValue = "true")
public class WorkloadListenerConfiguration {
    /** 只添加显式启用的本机监听器，既有业务端口与调用方式不变。 */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> workloadListener(Environment environment) {
        Connector connector = connector(environment);
        return factory -> factory.addAdditionalTomcatConnectors(connector);
    }

    static Connector connector(Environment environment) {
        try {
            int port = Integer.parseInt(required(environment, "port"));
            if (port < 1024 || port > 65535) throw new IllegalArgumentException();
            Path keyStore = privateFile(required(environment, "key-store-file"));
            Path trustStore = privateFile(required(environment, "trust-store-file"));
            String keyPassword = password(required(environment, "key-store-password-file"));
            String trustPassword = password(required(environment, "trust-store-password-file"));
            var host = new SSLHostConfig();
            // TLS 握手强制校验内部 CA，业务层仍独立校验精确 SPIFFE 身份与授权。
            host.setProtocols("TLSv1.2,+TLSv1.3");
            host.setCertificateVerification("required");
            host.setTruststoreFile(trustStore.toString());
            host.setTruststoreType("PKCS12");
            host.setTruststorePassword(trustPassword);
            var certificate = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
            certificate.setCertificateKeystoreFile(keyStore.toString());
            certificate.setCertificateKeystoreType("PKCS12");
            certificate.setCertificateKeystorePassword(keyPassword);
            host.addCertificate(certificate);
            var connector = new Connector(Http11NioProtocol.class.getName());
            connector.setPort(port);
            connector.setScheme("https");
            connector.setSecure(true);
            connector.setProperty("address", "127.0.0.1");
            connector.setProperty("SSLEnabled", "true");
            connector.addSslHostConfig(host);
            return connector;
        } catch (Exception exception) {
            // 配置错误不传播密码、私有路径或底层异常消息。
            throw new IllegalStateException("Workload listener configuration is invalid");
        }
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty("workload.listener." + name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static Path privateFile(String value) throws Exception {
        Path path = Path.of(value);
        if (!path.isAbsolute() || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) throw new IllegalArgumentException();
        return path;
    }

    private static String password(String value) throws Exception {
        Path path = privateFile(value);
        if (Files.size(path) > 4096) throw new IllegalArgumentException();
        String text = Files.readString(path).strip();
        if (text.isEmpty() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0)
            throw new IllegalArgumentException();
        return text;
    }
}

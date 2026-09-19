package com.yuyutian.mytools.task.scheduler.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证新增端口范围、双向 TLS、默认关闭及私有配置拒绝策略。 */
class WorkloadListenerConfigurationTest {
    @TempDir Path directory;

    @Test
    void keepsBusinessHttpAndRequiresNativeTlsOnLoopback() throws Exception {
        var factory = new TomcatServletWebServerFactory(23230);
        new WorkloadListenerConfiguration().workloadListener(environment()).customize(factory);
        assertThat(factory.getPort()).isEqualTo(23230);
        var connector = factory.getAdditionalTomcatConnectors().getFirst();
        assertThat(connector.getPort()).isEqualTo(24230);
        assertThat(connector.getSecure()).isTrue();
        assertThat(connector.getProperty("address").toString()).contains("127.0.0.1");
        assertThat(connector.findSslHostConfigs()[0].getCertificateVerificationAsString()).isEqualTo("REQUIRED");
        assertThat(connector.findSslHostConfigs()[0].getProtocols()).containsExactlyInAnyOrder("TLSv1.2", "TLSv1.3");
    }

    @Test
    void rejectsMissingConfigurationWithoutPrivateDetails() {
        reject(new MockEnvironment());
    }

    @Test
    void rejectsPrivilegedAndOutOfRangePorts() throws Exception {
        for (String port : new String[]{"0", "443", "65536", "invalid"}) reject(environment().withProperty("workload.listener.port", port));
    }

    @Test
    void rejectsSharedPasswordFiles() throws Exception {
        var env = environment();
        Files.setPosixFilePermissions(directory.resolve("password"), PosixFilePermissions.fromString("rw-r-----"));
        reject(env);
    }

    @Test
    void rejectsSymlinkAndOversizedPasswords() throws Exception {
        var env = environment();
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, directory.resolve("password"));
        reject(env.withProperty("workload.listener.key-store-password-file", link.toString()));
        env = environment();
        Files.writeString(directory.resolve("password"), "x".repeat(4097));
        reject(env);
    }

    @Test
    void staysDisabledUnlessExplicitlyEnabled() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(WorkloadListenerConfiguration.class);
            context.refresh();
            assertThat(context.containsBean("workloadListener")).isFalse();
        }
    }

    private MockEnvironment environment() throws Exception {
        for (String file : new String[]{"key.p12", "trust.p12", "password"}) {
            Path path = directory.resolve(file);
            Files.writeString(path, "test-only-randomly-unrelated");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        return new MockEnvironment().withProperty("workload.listener.port", "24230")
                .withProperty("workload.listener.key-store-file", directory.resolve("key.p12").toString())
                .withProperty("workload.listener.trust-store-file", directory.resolve("trust.p12").toString())
                .withProperty("workload.listener.key-store-password-file", directory.resolve("password").toString())
                .withProperty("workload.listener.trust-store-password-file", directory.resolve("password").toString());
    }

    private static void reject(MockEnvironment env) {
        assertThatThrownBy(() -> WorkloadListenerConfiguration.connector(env))
                .isInstanceOf(IllegalStateException.class).hasMessage("Workload listener configuration is invalid").hasNoCause();
    }
}


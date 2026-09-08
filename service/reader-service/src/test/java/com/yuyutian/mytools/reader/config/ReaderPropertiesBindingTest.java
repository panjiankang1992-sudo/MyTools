package com.yuyutian.mytools.reader.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("reader.audiobook-generation-enabled=true");

    @Test
    void shouldBindEmptyAndCommaSeparatedOwnerAllowlist() {
        contextRunner.withPropertyValues("reader.audiobook-allowed-owner-ids=").run(context -> {
            var properties = context.getBean(ReaderProperties.class);
            assertThat(properties.audiobookGenerationEnabled()).isTrue();
            assertThat(properties.audiobookAllowedOwnerIds()).isNullOrEmpty();
        });
        contextRunner.withPropertyValues("reader.audiobook-allowed-owner-ids=1201,1202").run(context -> {
            assertThat(context.getBean(ReaderProperties.class).audiobookAllowedOwnerIds()).containsExactlyInAnyOrder(1201L,
                    1202L);
        });
    }

    /** 读取记录型配置属性的测试上下文。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReaderProperties.class)
    static class PropertiesConfiguration {
    }
}

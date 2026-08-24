package com.yuyutian.mytools.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 配置绑定测试。
 */
@SpringBootTest(properties = {
        "gateway.media-route-enabled=true",
        "gateway.media-url=http://media-test",
        "gateway.media-token=test-token"
})
class GatewayPropertiesBindingTest {
    @Autowired
    private GatewayProperties properties;

    @Test
    void shouldBindMediaRouteConfiguration() {
        assertThat(properties.mediaRouteEnabled()).isTrue();
        assertThat(properties.mediaUrl()).isEqualTo("http://media-test");
        assertThat(properties.mediaToken()).isEqualTo("test-token");
    }
}

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
        "gateway.media-token=test-token",
        "gateway.app-catalog.route-enabled=true",
        "gateway.app-catalog.url=http://catalog-test",
        "gateway.dsh.route-enabled=true",
        "gateway.dsh.url=http://dsh-test"
})
class GatewayPropertiesBindingTest {
    @Autowired
    private GatewayProperties properties;
    @Autowired
    private AppCatalogGatewayProperties appCatalogProperties;
    @Autowired
    private DshGatewayProperties dshProperties;

    @Test
    void shouldBindMediaRouteConfiguration() {
        assertThat(properties.mediaRouteEnabled()).isTrue();
        assertThat(properties.mediaUrl()).isEqualTo("http://media-test");
        assertThat(properties.mediaToken()).isEqualTo("test-token");
        assertThat(appCatalogProperties.routeEnabled()).isTrue();
        assertThat(appCatalogProperties.url()).isEqualTo("http://catalog-test");
        assertThat(dshProperties.routeEnabled()).isTrue();
        assertThat(dshProperties.url()).isEqualTo("http://dsh-test");
        assertThat(properties.messagingUrl()).isEqualTo("http://127.0.0.1:23250");
    }
}

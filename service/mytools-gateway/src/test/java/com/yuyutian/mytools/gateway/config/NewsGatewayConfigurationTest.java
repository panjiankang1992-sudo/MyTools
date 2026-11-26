package com.yuyutian.mytools.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class NewsGatewayConfigurationTest {
    @Test void defaultRouteIsClosedAndProductionEnvironmentMapsToSameNewsIdentity() throws IOException {
        var defaults=bind(Map.of());
        assertThat(defaults.routeEnabled()).isFalse();assertThat(defaults.url()).isEqualTo("http://127.0.0.1:23350");assertThat(defaults.token()).isEmpty();
        var enabled=bind(Map.of("GATEWAY_NEWS_ROUTE_ENABLED","true","NEWS_SERVICE_URL","https://news.example.test","NEWS_INTERNAL_TOKEN","fixture-token"));
        assertThat(enabled.routeEnabled()).isTrue();assertThat(enabled.url()).isEqualTo("https://news.example.test");assertThat(enabled.token()).isEqualTo("fixture-token");
    }
    private NewsGatewayProperties bind(Map<String,Object> values) throws IOException {
        var environment=new StandardEnvironment();environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fixture",values));
        for(var source:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml")))environment.getPropertySources().addLast(source);
        return Binder.get(environment).bind("gateway.news",NewsGatewayProperties.class).orElseThrow(IllegalStateException::new);
    }
}

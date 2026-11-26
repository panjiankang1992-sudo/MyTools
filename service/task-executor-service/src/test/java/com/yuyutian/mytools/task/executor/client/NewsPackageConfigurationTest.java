package com.yuyutian.mytools.task.executor.client;

import com.yuyutian.mytools.task.executor.config.ExecutorProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class NewsPackageConfigurationTest {
    @Test void newsClusterAndRestrictedWorkerEnvironmentAreBoundFromDeploymentConfiguration() throws IOException {
        var environment=new StandardEnvironment();environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("NEWS_JOB_TOKEN","job-fixture","NEWS_ADMIN_TOKEN","admin-must-not-reach-package",
                "MESSAGING_OUTBOUND_WORKER_TOKEN","worker-fixture","MESSAGING_OUTBOUND_ADMIN_TOKEN","outbound-admin-must-not-reach-package")));
        for(var source:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml")))environment.getPropertySources().addLast(source);
        var binder=Binder.get(environment);
        var properties=binder.bind("executor",ExecutorProperties.class).orElseThrow(IllegalStateException::new);
        assertThat(properties.clusterNames()).contains("news","messaging");
        assertThat(SchedulerNodeClient.flattenedLabels(properties.labels())).containsEntry("news.node","absent").containsEntry("executor.node","executor-local");
        var generation=properties.scriptEnvironments().get("news_generate_edition");
        assertThat(generation).containsEntry("NEWS_JOB_TOKEN","job-fixture");assertThat(generation).containsOnlyKeys("NEWS_JOB_TOKEN","NEWS_SERVICE_URL");
        var outbound=properties.scriptEnvironments().get("message_send_channel_message");
        assertThat(outbound).containsEntry("MESSAGING_OUTBOUND_WORKER_TOKEN","worker-fixture").doesNotContainKey("MESSAGING_OUTBOUND_ADMIN_TOKEN");
        assertThat(outbound.values()).doesNotContain("outbound-admin-must-not-reach-package");
    }
}

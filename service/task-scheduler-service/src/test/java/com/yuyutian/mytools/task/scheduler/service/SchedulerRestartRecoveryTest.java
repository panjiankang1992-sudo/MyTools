package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.TaskSchedulerApplication;
import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.FailurePolicy;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.ReportStepExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerRestartRecoveryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReplayReportsAfterSchedulerProcessRestart() {
        String databaseUrl = "jdbc:h2:file:" + temporaryDirectory.resolve("scheduler-restart").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        UUID reportRequestId = UUID.randomUUID();
        UUID completionRequestId = UUID.randomUUID();
        UUID executionId;
        UUID leaseToken;
        UUID stepId;

        try (ConfigurableApplicationContext first = start(databaseUrl)) {
            ExecutionTopologyService topology = first.getBean(ExecutionTopologyService.class);
            TaskDefinitionService definitions = first.getBean(TaskDefinitionService.class);
            TaskStepService steps = first.getBean(TaskStepService.class);
            TaskInstanceService tasks = first.getBean(TaskInstanceService.class);
            TaskDispatchService dispatch = first.getBean(TaskDispatchService.class);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            var cluster = topology.createCluster(new CreateExecutionClusterRequest(
                    "restart_cluster_" + suffix, "Restart recovery", "LEAST_RUNNING", 1, Map.of(), true));
            UUID instanceId = UUID.randomUUID();
            var node = topology.registerNode(new RegisterExecutorNodeRequest(
                    "restart_node_" + suffix, instanceId.toString(), Map.of("runtimes", List.of("shell")),
                    Map.of(), 1, Set.of(cluster.name())));
            var definition = definitions.create(new CreateTaskDefinitionRequest(
                    "restart_task_" + suffix, "Restart recovery", TaskType.IMMEDIATE, 120,
                    cluster.id(), null, null, ExecutionMode.SINGLE_NODE, true, 1,
                    "SKIP", "IGNORE", Map.of(), Map.of()));
            var step = steps.create(definition.id(), new CreateTaskStepRequest(
                    "run", "Run restart probe", StepKind.NORMAL, "restart_probe", "1.0.0",
                    "main.sh", List.of(), true, 60, FailurePolicy.FAIL_TASK, 10, 1));
            tasks.create(new CreateTaskRequest(definition.name(), "restart_" + suffix,
                    "TEST", suffix, null, 50, Map.of()));
            var claimed = dispatch.claim(new ClaimTaskRequest(node.id(), instanceId, 60)).orElseThrow();
            executionId = claimed.executionId();
            leaseToken = claimed.leaseToken();
            stepId = step.id();
            assertEquals("accepted", dispatch.reportStep(executionId, new ReportStepExecutionRequest(
                    reportRequestId, leaseToken, stepId, 1, TaskStatus.SUCCEEDED,
                    0, Map.of("value", "ok"), null, null)).outcome());
        }

        try (ConfigurableApplicationContext restarted = start(databaseUrl)) {
            TaskDispatchService dispatch = restarted.getBean(TaskDispatchService.class);
            var stepReplay = dispatch.reportStep(executionId, new ReportStepExecutionRequest(
                    reportRequestId, leaseToken, stepId, 1, TaskStatus.SUCCEEDED,
                    0, Map.of("value", "ok"), null, null));
            assertTrue(stepReplay.replayed());
            CompleteExecutionRequest completion = new CompleteExecutionRequest(
                    completionRequestId, leaseToken, TaskStatus.SUCCEEDED);
            assertEquals("accepted", dispatch.complete(executionId, completion).outcome());
            assertTrue(dispatch.complete(executionId, completion).replayed());
        }
    }

    private ConfigurableApplicationContext start(String databaseUrl) {
        return new SpringApplicationBuilder(TaskSchedulerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + databaseUrl,
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--task.security.required=false",
                        "--task.node-registration.enforce=false",
                        "--task.scheduler.cron-scan-delay-ms=60000",
                        "--task.scheduler.deadline-scan-delay-ms=60000",
                        "--task.scheduler.cancellation-scan-delay-ms=60000",
                        "--task.node-health.scan-delay-ms=60000");
    }
}

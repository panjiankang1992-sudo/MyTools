package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.FailurePolicy;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.AssignClusterNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TaskInstanceServiceTest {

    @Autowired
    private TaskInstanceService service;

    @Autowired
    private TaskDefinitionService definitionService;

    @Autowired
    private TaskStepService stepService;

    @Autowired
    private ExecutionTopologyService topologyService;

    @Test
    void shouldCreateIdempotentlyAndCancel() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String taskName = "media_generate_tags_" + suffix;
        definitionService.create(new CreateTaskDefinitionRequest(
                taskName, "Generate media tags", TaskType.IMMEDIATE, 600, null, null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        CreateTaskRequest request = new CreateTaskRequest(
                taskName, "asset_1_v1_" + suffix, "MEDIA_ASSET", "1", null, 50, Map.of("assetId", "1")
        );

        var first = service.create(request);
        var second = service.create(request);

        assertEquals(first.id(), second.id());
        assertEquals(TaskStatus.CANCELLING, service.cancel(first.id()).status());
    }

    @Test
    void shouldPersistStepsAndManyToManyExecutionTopology() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "media_cluster_" + suffix, "Media workers", "LEAST_RUNNING", 20, Map.of("gpu", true), true
        ));
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "media-node-" + suffix, "instance-1", Map.of("python", "3.12"), Map.of("gpu", true), 4
        ));
        topologyService.assignNode(cluster.id(), new AssignClusterNodeRequest(node.id(), 100, 0, true));
        assertEquals(node.id(), topologyService.heartbeat(node.id(), "instance-1", 1).id());

        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "media_probe_" + suffix, "Probe media", TaskType.IMMEDIATE, 120, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "probe", "Run ffprobe", StepKind.NORMAL, "media_probe", "1.0.0", "scripts/main.py",
                List.of("--asset-id", "${parameters.assetId}"), true, 90, FailurePolicy.FAIL_TASK, 10, 2
        ));

        assertEquals(1, stepService.list(definition.id()).size());
        assertTrue(topologyService.listClusters().stream().anyMatch(item -> item.id().equals(cluster.id())));
        assertTrue(topologyService.listNodes().stream().anyMatch(item -> item.id().equals(node.id())));
    }
}

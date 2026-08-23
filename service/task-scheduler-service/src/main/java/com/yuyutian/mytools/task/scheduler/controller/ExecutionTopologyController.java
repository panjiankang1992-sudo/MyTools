package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.AssignClusterNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionClusterView;
import com.yuyutian.mytools.task.scheduler.model.ExecutorNodeView;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.service.ExecutionTopologyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 执行集群与节点控制器。
 */
@RestController
@RequestMapping("/api/v1/execution-topology")
public class ExecutionTopologyController {

    private final ExecutionTopologyService service;

    /**
     * 创建执行拓扑控制器。
     *
     * @param service 执行拓扑服务
     */
    public ExecutionTopologyController(ExecutionTopologyService service) {
        this.service = service;
    }

    /**
     * 创建执行集群。
     *
     * @param request 创建请求
     * @return 集群视图
     */
    @PostMapping("/clusters")
    @ResponseStatus(HttpStatus.CREATED)
    public ExecutionClusterView createCluster(@Valid @RequestBody CreateExecutionClusterRequest request) {
        return service.createCluster(request);
    }

    /**
     * 查询全部集群。
     *
     * @return 集群列表
     */
    @GetMapping("/clusters")
    public List<ExecutionClusterView> listClusters() {
        return service.listClusters();
    }

    /**
     * 注册执行节点。
     *
     * @param request 注册请求
     * @return 节点视图
     */
    @PostMapping("/nodes/register")
    public ExecutorNodeView registerNode(@Valid @RequestBody RegisterExecutorNodeRequest request) {
        return service.registerNode(request);
    }

    /**
     * 上报节点心跳。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @param runningTasks 运行任务数
     * @return 节点视图
     */
    @PostMapping("/nodes/{nodeId}/heartbeat")
    public ExecutorNodeView heartbeat(@PathVariable UUID nodeId,
                                      @RequestHeader("X-Executor-Instance-Id") String instanceId,
                                      @RequestHeader(value = "X-Running-Tasks", defaultValue = "0") int runningTasks) {
        return service.heartbeat(nodeId, instanceId, runningTasks);
    }

    /**
     * 查询全部节点。
     *
     * @return 节点列表
     */
    @GetMapping("/nodes")
    public List<ExecutorNodeView> listNodes() {
        return service.listNodes();
    }

    /**
     * 将节点分配到执行集群。
     *
     * @param clusterId 集群标识
     * @param request 分配请求
     */
    @PostMapping("/clusters/{clusterId}/nodes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignNode(@PathVariable UUID clusterId, @Valid @RequestBody AssignClusterNodeRequest request) {
        service.assignNode(clusterId, request);
    }
}

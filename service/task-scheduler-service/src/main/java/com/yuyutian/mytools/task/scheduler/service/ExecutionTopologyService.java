package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.AssignClusterNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionClusterView;
import com.yuyutian.mytools.task.scheduler.model.ExecutorNodeView;
import com.yuyutian.mytools.task.scheduler.model.NodeStatus;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 执行集群与节点管理服务。
 */
@Service
public class ExecutionTopologyService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    /**
     * 创建执行拓扑服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param jsonColumnMapper JSON 转换器
     */
    public ExecutionTopologyService(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    /**
     * 创建执行集群。
     *
     * @param request 创建请求
     * @return 集群视图
     */
    @Transactional
    public ExecutionClusterView createCluster(CreateExecutionClusterRequest request) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO execution_cluster
                (id, name, description, dispatch_strategy, max_concurrent_tasks, labels_json, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), request.name(), request.description(), request.dispatchStrategy(),
                request.maxConcurrentTasks(), jsonColumnMapper.write(request.labels()), request.enabled(),
                Timestamp.from(now), Timestamp.from(now));
        return getCluster(id);
    }

    /**
     * 查询全部执行集群。
     *
     * @return 集群列表
     */
    public List<ExecutionClusterView> listClusters() {
        return jdbcTemplate.query("SELECT * FROM execution_cluster ORDER BY name", this::mapCluster);
    }

    /**
     * 注册或刷新执行节点。
     *
     * @param request 注册请求
     * @return 节点视图
     */
    @Transactional
    public ExecutorNodeView registerNode(RegisterExecutorNodeRequest request) {
        Instant now = Instant.now();
        List<ExecutorNodeView> existing = jdbcTemplate.query(
                "SELECT * FROM executor_node WHERE name = ?", this::mapNode, request.name());
        if (!existing.isEmpty()) {
            UUID id = existing.getFirst().id();
            jdbcTemplate.update("""
                    UPDATE executor_node SET instance_id = ?, status = ?, capabilities_json = ?, labels_json = ?,
                    max_concurrent_tasks = ?, enabled = TRUE, last_heartbeat_at = ?, updated_at = ? WHERE id = ?
                    """, request.instanceId(), NodeStatus.ONLINE.name(),
                    jsonColumnMapper.write(request.capabilities()), jsonColumnMapper.write(request.labels()),
                    request.maxConcurrentTasks(), Timestamp.from(now), Timestamp.from(now), id.toString());
            return getNode(id);
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO executor_node
                (id, name, instance_id, status, capabilities_json, labels_json, max_concurrent_tasks,
                 running_tasks, enabled, last_heartbeat_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), request.name(), request.instanceId(), NodeStatus.ONLINE.name(),
                jsonColumnMapper.write(request.capabilities()), jsonColumnMapper.write(request.labels()),
                request.maxConcurrentTasks(), 0, true, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return getNode(id);
    }

    /**
     * 更新节点心跳。
     *
     * @param nodeId 节点标识
     * @param instanceId 启动实例标识
     * @param runningTasks 运行任务数
     * @return 节点视图
     */
    @Transactional
    public ExecutorNodeView heartbeat(UUID nodeId, String instanceId, int runningTasks) {
        int updated = jdbcTemplate.update("""
                UPDATE executor_node SET last_heartbeat_at = ?, running_tasks = ?, status = ?, updated_at = ?
                WHERE id = ? AND instance_id = ? AND enabled = TRUE
                """, Timestamp.from(Instant.now()), runningTasks, NodeStatus.ONLINE.name(),
                Timestamp.from(Instant.now()), nodeId.toString(), instanceId);
        if (updated != 1) {
            throw new IllegalArgumentException("Executor node instance does not exist");
        }
        return getNode(nodeId);
    }

    /**
     * 将节点加入集群。
     *
     * @param clusterId 集群标识
     * @param request 分配请求
     */
    @Transactional
    public void assignNode(UUID clusterId, AssignClusterNodeRequest request) {
        getCluster(clusterId);
        getNode(request.nodeId());
        try {
            jdbcTemplate.update("""
                    INSERT INTO cluster_node (cluster_id, node_id, weight, priority, enabled, joined_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, clusterId.toString(), request.nodeId().toString(), request.weight(), request.priority(),
                    request.enabled(), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException exception) {
            jdbcTemplate.update("""
                    UPDATE cluster_node SET weight = ?, priority = ?, enabled = ?
                    WHERE cluster_id = ? AND node_id = ?
                    """, request.weight(), request.priority(), request.enabled(),
                    clusterId.toString(), request.nodeId().toString());
        }
    }

    /**
     * 查询全部执行节点。
     *
     * @return 节点列表
     */
    public List<ExecutorNodeView> listNodes() {
        return jdbcTemplate.query("SELECT * FROM executor_node ORDER BY name", this::mapNode);
    }

    private ExecutionClusterView getCluster(UUID id) {
        return jdbcTemplate.query("SELECT * FROM execution_cluster WHERE id = ?", this::mapCluster, id.toString())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Execution cluster does not exist"));
    }

    private ExecutorNodeView getNode(UUID id) {
        return jdbcTemplate.query("SELECT * FROM executor_node WHERE id = ?", this::mapNode, id.toString())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Executor node does not exist"));
    }

    private ExecutionClusterView mapCluster(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExecutionClusterView(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("name"),
                resultSet.getString("description"), resultSet.getString("dispatch_strategy"),
                resultSet.getInt("max_concurrent_tasks"), jsonColumnMapper.read(resultSet.getString("labels_json")),
                resultSet.getBoolean("enabled"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private ExecutorNodeView mapNode(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExecutorNodeView(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("name"),
                resultSet.getString("instance_id"), NodeStatus.valueOf(resultSet.getString("status")),
                jsonColumnMapper.read(resultSet.getString("capabilities_json")),
                jsonColumnMapper.read(resultSet.getString("labels_json")),
                resultSet.getInt("max_concurrent_tasks"), resultSet.getInt("running_tasks"),
                resultSet.getBoolean("enabled"), resultSet.getTimestamp("last_heartbeat_at").toInstant());
    }
}

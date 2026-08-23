package com.yuyutian.mytools.task.executor.client;

import java.util.UUID;

/**
 * 调度服务返回的节点注册信息。
 *
 * @param id 节点标识
 * @param name 节点名称
 * @param instanceId 启动实例标识
 */
public record ExecutorNodeRegistration(UUID id, String name, String instanceId) {
}

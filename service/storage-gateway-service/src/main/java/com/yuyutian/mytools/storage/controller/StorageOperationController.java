package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.model.CreateOperationRequest;
import com.yuyutian.mytools.storage.model.FinishOperationRequest;
import com.yuyutian.mytools.storage.model.OperationItemBatch;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.ReconciliationDigest;
import com.yuyutian.mytools.storage.model.RemoteJobView;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageOperationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 内部异步存储操作接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/operations")
public class StorageOperationController {
    private final StorageOperationService operationService;
    private final InternalAuthorizer authorizer;

    /**
     * 创建操作控制器。
     *
     * @param operationService 操作服务
     * @param authorizer 内部鉴权器
     */
    public StorageOperationController(StorageOperationService operationService, InternalAuthorizer authorizer) {
        this.operationService = operationService;
        this.authorizer = authorizer;
    }

    /**
     * 创建异步操作。
     *
     * @param authorization 内部授权头
     * @param request 创建请求
     * @return 操作
     */
    @PostMapping
    public ResponseEntity<StorageOperation> create(@RequestHeader("Authorization") String authorization,
                                                   @Valid @RequestBody CreateOperationRequest request) {
        authorizer.require(authorization);
        return ResponseEntity.accepted().body(operationService.create(request));
    }

    /**
     * 查询异步操作。
     *
     * @param id 操作标识
     * @param authorization 内部授权头
     * @return 操作
     */
    @GetMapping("/{id}")
    public StorageOperation get(@PathVariable UUID id, @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return operationService.require(id);
    }

    /** 读取成功扫描快照摘要。 @param id 操作标识 @param authorization 授权头 @return 摘要 */
    @GetMapping("/{id}/digest")
    public ReconciliationDigest digest(@PathVariable UUID id,
        @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization); return operationService.digest(id);
    }

    /**
     * 启动跨 Provider 后台任务。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 最新操作
     */
    @PostMapping("/{id}/remote-job/start")
    public StorageOperation startRemoteJob(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return operationService.startRemoteJob(id);
    }

    /**
     * 查询跨 Provider 后台任务并对账终态。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 远端任务状态
     */
    @GetMapping("/{id}/remote-job")
    public RemoteJobView remoteJob(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return operationService.remoteJob(id);
    }

    /**
     * 停止跨 Provider 后台任务。
     *
     * @param id 操作标识
     * @param authorization 授权头
     */
    @PostMapping("/{id}/remote-job/stop")
    public void stopRemoteJob(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        operationService.stopRemoteJob(id);
    }

    /**
     * 合并扫描结果批次。
     *
     * @param id 操作标识
     * @param authorization 内部授权头
     * @param batch 对象批次
     * @return 操作
     */
    @PostMapping("/{id}/items")
    public StorageOperation merge(@PathVariable UUID id, @RequestHeader("Authorization") String authorization,
                                  @Valid @RequestBody OperationItemBatch batch) {
        authorizer.require(authorization);
        return operationService.mergeItems(id, batch.items());
    }

    /**
     * 设置操作终态。
     *
     * @param id 操作标识
     * @param authorization 内部授权头
     * @param request 终态请求
     * @return 操作
     */
    @PostMapping("/{id}/finish")
    public StorageOperation finish(@PathVariable UUID id, @RequestHeader("Authorization") String authorization,
                                   @Valid @RequestBody FinishOperationRequest request) {
        authorizer.require(authorization);
        return operationService.finish(id, request.status(), request.errorCode());
    }
}

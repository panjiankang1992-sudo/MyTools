package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.model.CreateOperationRequest;
import com.yuyutian.mytools.storage.model.FinishOperationRequest;
import com.yuyutian.mytools.storage.model.OperationItemBatch;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.ReconciliationDigest;
import com.yuyutian.mytools.storage.model.RemoteJobView;
import com.yuyutian.mytools.storage.model.AbortMoveRequest;
import com.yuyutian.mytools.storage.model.MoveProgress;
import com.yuyutian.mytools.storage.model.NativeWriteResult;
import com.yuyutian.mytools.storage.model.RemoteContent;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageOperationService;
import com.yuyutian.mytools.storage.service.StorageMoveService;
import com.yuyutian.mytools.storage.service.StorageNativeCopyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

import java.util.UUID;

/**
 * 内部异步存储操作接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/operations")
public class StorageOperationController {
    private final StorageOperationService operationService;
    private final InternalAuthorizer authorizer;
    private final StorageMoveService moveService;
    private final StorageNativeCopyService nativeCopyService;

    /**
     * 创建操作控制器。
     *
     * @param operationService 操作服务
     * @param authorizer 内部鉴权器
     * @param moveService 远端移动服务
     * @param nativeCopyService 原生复制服务
     */
    public StorageOperationController(StorageOperationService operationService, InternalAuthorizer authorizer,
                                      StorageMoveService moveService, StorageNativeCopyService nativeCopyService) {
        this.operationService = operationService;
        this.authorizer = authorizer;
        this.moveService = moveService;
        this.nativeCopyService = nativeCopyService;
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
     * 流式读取原生复制操作定义的来源对象。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 来源对象流
     */
    @GetMapping("/{id}/native-copy/source")
    public ResponseEntity<InputStreamResource> nativeCopySource(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return contentResponse(nativeCopyService.source(id));
    }

    /**
     * 流式写入原生复制操作定义的目标对象。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @param contentLength 精确内容长度
     * @param sha256 内容摘要
     * @param request HTTP 请求
     * @return 写入结果
     * @throws IOException 无法读取请求流
     */
    @PutMapping(path = "/{id}/native-copy/target", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public NativeWriteResult writeNativeCopyTarget(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization,
            @RequestParam long contentLength, @RequestParam String sha256,
            HttpServletRequest request) throws IOException {
        authorizer.require(authorization);
        if (request.getContentLengthLong() != contentLength) {
            throw new IllegalArgumentException(com.yuyutian.mytools.storage.model.ErrorCode.CONTENT_MISMATCH.code());
        }
        return nativeCopyService.writeTarget(id, request.getInputStream(), contentLength, sha256);
    }

    /**
     * 流式读取原生复制操作定义的目标对象用于复验。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 目标对象流
     */
    @GetMapping("/{id}/native-copy/target")
    public ResponseEntity<InputStreamResource> nativeCopyTarget(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return contentResponse(nativeCopyService.target(id));
    }

    /**
     * 补偿删除原生复制操作定义的目标对象。
     *
     * @param id 操作标识
     * @param authorization 授权头
     */
    @DeleteMapping("/{id}/native-copy/target")
    public void deleteNativeCopyTarget(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        nativeCopyService.deleteTarget(id);
    }

    private ResponseEntity<InputStreamResource> contentResponse(RemoteContent content) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (content.contentLength() >= 0) {
            response.contentLength(content.contentLength());
        }
        return response.body(new InputStreamResource(content.stream()));
    }

    /**
     * 推进远端移动状态机。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 移动进度
     */
    @PostMapping("/{id}/move/advance")
    public MoveProgress advanceMove(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return moveService.advance(id);
    }

    /**
     * 中止远端移动并按当前阶段补偿或前向收敛。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @param request 中止请求
     * @return 移动进度
     */
    @PostMapping("/{id}/move/abort")
    public MoveProgress abortMove(@PathVariable UUID id, @RequestHeader("Authorization") String authorization,
                                  @Valid @RequestBody AbortMoveRequest request) {
        authorizer.require(authorization);
        return moveService.abort(id, request.status());
    }

    /**
     * 将截止前无法收敛的移动记录为待恢复。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 恢复状态
     */
    @PostMapping("/{id}/move/recovery-required")
    public MoveProgress markMoveRecoveryRequired(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return moveService.markRecoveryRequired(id);
    }

    /**
     * 推进远端移动恢复清理。
     *
     * @param id 操作标识
     * @param authorization 授权头
     * @return 恢复进度
     */
    @PostMapping("/{id}/move/recover")
    public MoveProgress recoverMove(@PathVariable UUID id,
            @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return moveService.recover(id);
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

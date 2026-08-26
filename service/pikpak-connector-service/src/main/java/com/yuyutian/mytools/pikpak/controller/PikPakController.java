package com.yuyutian.mytools.pikpak.controller;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;

import com.yuyutian.mytools.pikpak.service.InternalAuthorizer;
import com.yuyutian.mytools.pikpak.service.PikPakOperationService;
import com.yuyutian.mytools.pikpak.service.PikPakWatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** PikPak 账户和离线操作内部接口。 */
@RestController
@RequestMapping("/api/internal/v1/pikpak")
public class PikPakController {
    private final PikPakOperationService service;
    private final InternalAuthorizer authorizer;
    private final PikPakWatchService watchService;

    /** 创建控制器。 @param service 操作服务 @param authorizer 鉴权器 */
    public PikPakController(PikPakOperationService service, InternalAuthorizer authorizer,
                            PikPakWatchService watchService) {
        this.service = service;
        this.authorizer = authorizer;
        this.watchService = watchService;
    }

    /** 登记账户。 @param authorization 授权头 @param request 请求 @return 账户 */
    @PostMapping("/accounts")
    public AccountView register(@RequestHeader("Authorization") String authorization,
                            @Valid @RequestBody RegisterAccountRequest request) {
        authorizer.require(authorization);
        return service.registerAccount(request);
    }

    /** 创建离线操作。 @param authorization 授权头 @param request 请求 @return 操作 */
    @PostMapping("/operations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView create(@RequestHeader("Authorization") String authorization,
                                @Valid @RequestBody CreateOperationRequest request) {
        authorizer.require(authorization);
        return service.create(request);
    }

    /** 推进离线操作。 @param id 操作标识 @param authorization 授权头 @param request 推进请求 @return 操作 */
    @PostMapping("/operations/{id}/advance")
    public OperationView advance(@PathVariable UUID id,
        @RequestHeader("Authorization") String authorization,
        @RequestBody(required=false) AdvanceRequest request) {
        authorizer.require(authorization);
        return service.advance(id, request == null ? null : request.magnetUri());
    }

    /** 查询离线操作。 @param id 操作标识 @param authorization 授权头 @return 操作 */
    @GetMapping("/operations/{id}")
    public OperationView get(@PathVariable UUID id,
        @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return service.get(id);
    }

    /** 取消离线操作。 @param id 操作标识 @param authorization 授权头 @return 操作 */
    @PostMapping("/operations/{id}/cancel")
    public OperationView cancel(@PathVariable UUID id,
        @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return service.cancel(id);
    }

    /** 登记固定目录 watcher。 @param authorization 授权 @param request 请求 @return 配置 */
    @PostMapping("/watchers")
    public Watcher registerWatcher(@RequestHeader("Authorization") String authorization,
                                   @Valid @RequestBody RegisterWatcherRequest request) {
        authorizer.require(authorization);
        return watchService.register(request);
    }

    /** 扫描一次固定目录。 @param accountId 账户 @param authorization 授权 @return 稳定批次 */
    @PostMapping("/watchers/{accountId}/scan")
    public WatchScanView scanWatcher(@PathVariable UUID accountId,
                                     @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return watchService.scan(accountId);
    }

    /** 扫描全部启用 watcher。 @param authorization 授权 @return 稳定批次 */
    @PostMapping("/watchers/scan")
    public List<WatchScanView> scanAllWatchers(@RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return watchService.scanAll();
    }

    /** 归档一个已经下载成功的批次。 @param batchId 批次 @param authorization 授权 @return 批次 */
    @PostMapping("/watch-batches/{batchId}/archive")
    public WatchBatchView archiveWatcherBatch(@PathVariable UUID batchId,
                                               @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return watchService.archive(batchId);
    }

    /** 查询 watcher 批次。 @param batchId 批次 @param authorization 授权 @return 批次 */
    @GetMapping("/watch-batches/{batchId}")
    public WatchBatchView getWatcherBatch(@PathVariable UUID batchId,
                                          @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return watchService.get(batchId);
    }

    /** 首次推进时携带但不持久化的 magnet URI。 */
    public record AdvanceRequest(@Size(max=8192) String magnetUri) { }
}

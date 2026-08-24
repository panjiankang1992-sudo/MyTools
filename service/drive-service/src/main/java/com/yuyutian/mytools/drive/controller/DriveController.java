package com.yuyutian.mytools.drive.controller;

import com.yuyutian.mytools.drive.config.DriveConfiguration.InternalToken;
import com.yuyutian.mytools.drive.model.DriveModels.*;
import com.yuyutian.mytools.drive.service.DriveService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/** Drive 内部 API。 */
@RestController
@RequestMapping("/internal/v1/drive")
public class DriveController {
    private final DriveService service; private final InternalToken token;
    /** 创建控制器。 @param service 服务 @param token 内部令牌 */
    public DriveController(DriveService service, InternalToken token) { this.service=service; this.token=token; }
    /** 登记账户。 @param authorization 授权头 @param request 请求 @return 账户 */
    @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED)
    public AccountView register(@RequestHeader("Authorization") String authorization, @Valid @RequestBody RegisterAccountRequest request) {
        authorize(authorization); return service.register(request);
    }
    /** 查询所有者的账户。 @param authorization 授权头 @param ownerId 所有者 @return 账户列表 */
    @GetMapping("/accounts")
    public List<AccountView> accounts(@RequestHeader("Authorization") String authorization,@RequestParam long ownerId) {
        authorize(authorization); return service.listAccounts(ownerId);
    }
    /** 写入索引批次。 @param authorization 授权头 @param id 账户 @param request 请求 @return 结果 */
    @PostMapping("/accounts/{id}/index-batches")
    public IndexBatchView ingest(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
        @Valid @RequestBody IndexBatchRequest request) { authorize(authorization); return service.ingest(id,request); }
    /** 查询索引。 @param authorization 授权头 @param id 账户 @param ownerId 所有者 @param parentPath 父路径 @return 子项 */
    @GetMapping("/accounts/{id}/items")
    public List<ItemView> list(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
        @RequestParam long ownerId, @RequestParam(defaultValue="") String parentPath) {
        authorize(authorization); return service.list(id,ownerId,parentPath);
    }
    /** 创建索引刷新任务。 @param authorization 授权头 @param id 账户 @param ownerId 所有者 @param request 请求 @return 操作 */
    @PostMapping("/accounts/{id}/refresh-index") @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView refreshIndex(@RequestHeader("Authorization") String authorization,@PathVariable UUID id,
        @RequestParam long ownerId,@Valid @RequestBody RefreshIndexRequest request) {
        authorize(authorization); return service.refreshIndex(id,ownerId,request);
    }
    /** 创建受控对象复制任务。 @param authorization 授权头 @param id 来源账户 @param ownerId 所有者 @param request 请求 @return 操作 */
    @PostMapping("/accounts/{id}/copy-object") @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView copyObject(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
        @RequestParam long ownerId, @Valid @RequestBody CopyObjectRequest request) {
        authorize(authorization); return service.copyObject(id, ownerId, request);
    }
    /** 创建受控递归复制任务。 @param authorization 授权头 @param id 来源账户 @param ownerId 所有者 @param request 请求 @return 操作 */
    @PostMapping("/accounts/{id}/copy-tree") @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView copyTree(@RequestHeader("Authorization") String authorization, @PathVariable UUID id,
        @RequestParam long ownerId, @Valid @RequestBody CopyTreeRequest request) {
        authorize(authorization); return service.copyTree(id, ownerId, request);
    }
    /** 查询操作。 @param authorization 授权头 @param id 操作 @param ownerId 所有者 @return 操作 */
    @GetMapping("/operations/{id}")
    public OperationView operation(@RequestHeader("Authorization") String authorization,@PathVariable UUID id,
        @RequestParam long ownerId) { authorize(authorization); return service.getOperation(id,ownerId); }
    /** 取消操作。 @param authorization 授权头 @param id 操作 @param ownerId 所有者 @return 操作 */
    @PostMapping("/operations/{id}/cancel")
    public OperationView cancel(@RequestHeader("Authorization") String authorization,@PathVariable UUID id,
        @RequestParam long ownerId) { authorize(authorization); return service.cancelOperation(id,ownerId); }
    /** 扫描一个受控远端目录。 @param authorization 授权头 @param id 账户 @param path 路径 @return 索引候选 */
    @GetMapping("/accounts/{id}/scan")
    public List<IndexItem> scan(@RequestHeader("Authorization") String authorization,@PathVariable UUID id,
        @RequestParam(defaultValue="") String path) { authorize(authorization); return service.scan(id,path); }
    /** 绑定 Storage Gateway Provider。 @param authorization 授权头 @param id 账户 @param request 绑定请求 */
    @PutMapping("/accounts/{id}/storage-provider") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bindStorageProvider(@RequestHeader("Authorization") String authorization,@PathVariable UUID id,
        @Valid @RequestBody BindStorageProviderRequest request) {
        authorize(authorization); service.bindStorageProvider(id,request.storageProviderId());
    }
    /** 结束索引运行。 @param authorization 授权头 @param id 账户 @param runId 运行 @param status 终态 */
    @PostMapping("/accounts/{id}/index-runs/{runId}/{status}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finishRun(@RequestHeader("Authorization") String authorization,@PathVariable UUID id,
        @PathVariable UUID runId,@PathVariable String status) { authorize(authorization); service.finishRun(id,runId,status); }
    private void authorize(String authorization) {
        byte[] expected=("Bearer "+token.value()).getBytes(StandardCharsets.UTF_8);
        if(token.value().isBlank() || !MessageDigest.isEqual(expected,authorization.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("drive internal authorization failed");
    }
}

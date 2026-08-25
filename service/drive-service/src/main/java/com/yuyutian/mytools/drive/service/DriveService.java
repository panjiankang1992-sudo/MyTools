package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import com.yuyutian.mytools.drive.repository.DriveRepository;
import com.yuyutian.mytools.drive.connector.RcloneConnector;
import com.yuyutian.mytools.drive.connector.StorageGatewayConnector;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;

/** Drive 领域服务。 */
@Service
public class DriveService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DriveService.class);
    private final DriveRepository repository; private final TransactionTemplate transactions;
    private final RcloneConnector legacyConnector; private final StorageGatewayConnector storageConnector;
    private final ScanMode scanMode;
    private final DriveTaskSchedulerClient schedulerClient;
    /** 创建领域服务。 @param repository 仓储 @param transactions 事务模板 */
    public DriveService(DriveRepository repository, TransactionTemplate transactions, RcloneConnector legacyConnector,
                        StorageGatewayConnector storageConnector,
                        DriveTaskSchedulerClient schedulerClient,
                        @Value("${drive.storage-scan-mode:LEGACY}") String scanMode) {
        this.repository=repository; this.transactions=transactions; this.legacyConnector=legacyConnector;
        this.storageConnector=storageConnector; this.schedulerClient=schedulerClient;
        this.scanMode=ScanMode.valueOf(scanMode.toUpperCase(java.util.Locale.ROOT));
    }
    /** 登记账户。 @param request 请求 @return 账户 */
    public AccountView register(RegisterAccountRequest request) { return transactions.execute(s -> repository.register(request)); }
    /** 校验或迁移冻结的旧账户批次。 @param request 迁移批次 @return 批次证据 */
    public LegacyAccountMigrationResult migrateLegacyAccounts(LegacyAccountMigrationBatch request) {
        return transactions.execute(status -> {
            LegacyAccountMigrationResult result = repository.migrateLegacyAccounts(request);
            // dry-run 必须执行完整约束校验，但不得保留账户或迁移审计。
            if (request.dryRun()) status.setRollbackOnly();
            return result;
        });
    }
    /** 查询正式旧账户迁移的目标集合证据。 @param migrationKey 迁移键 @return 集合证据 */
    public LegacyAccountMigrationEvidence legacyAccountMigrationEvidence(String migrationKey) {
        if (migrationKey == null || !migrationKey.matches("^[A-Za-z0-9._:-]{1,128}$"))
            throw new IllegalArgumentException("drive migration key is invalid");
        return repository.legacyAccountMigrationEvidence(migrationKey);
    }
    /** 查询所有者的账户。 @param ownerId 所有者标识 @return 账户列表 */
    public List<AccountView> listAccounts(long ownerId) { return repository.listAccounts(ownerId); }
    /** 写入索引批次。 @param id 账户 @param request 请求 @return 批次结果 */
    public IndexBatchView ingest(UUID id, IndexBatchRequest request) {
        for (IndexItem item : request.items()) {
            if (!normalize(item.remotePath()).equals(item.remotePath())
                    || !normalize(item.parentPath()).equals(item.parentPath())) {
                throw new IllegalArgumentException("drive index path is not normalized");
            }
        }
        return transactions.execute(s -> repository.ingest(require(id), request));
    }
    /** 查询账户的直接子项。 @param id 账户 @param ownerId 所有者 @param parentPath 父路径 @return 子项 */
    public List<ItemView> list(UUID id, long ownerId, String parentPath) {
        AccountView account=require(id); if (account.ownerId()!=ownerId) throw new IllegalArgumentException("drive account not found");
        return repository.list(id, normalize(parentPath));
    }
    /** 流式读取当前所有者的单个索引文件。 @param id 账户 @param ownerId 所有者 @param path 文件路径 @param maximumBytes 最大字节数 @return 文件流 */
    public ItemContent content(UUID id, long ownerId, String path, long maximumBytes) {
        requireOwner(id, ownerId);
        if (maximumBytes <= 0 || maximumBytes > 100L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("drive content maximum bytes is invalid");
        }
        String normalized = normalizeRequired(path);
        String parent = parent(normalized);
        ItemView item = repository.list(id, parent).stream()
                .filter(value -> value.remotePath().equals(normalized) && !value.directory()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("drive item not found"));
        if (item.sizeBytes() > maximumBytes) throw new IllegalArgumentException("drive item is too large");
        UUID providerId = repository.findStorageProvider(id)
                .orElseThrow(() -> new IllegalStateException("drive storage provider is not bound"));
        return storageConnector.content(providerId, normalized, maximumBytes, item.displayName(), item.mimeType());
    }
    /** 从受控 connector 列出目录。 @param id 账户 @param path 路径 @return 索引候选 */
    public List<IndexItem> scan(UUID id,String path) {
        AccountView account=require(id); String normalized=normalize(path);
        if(scanMode==ScanMode.LEGACY) return legacyConnector.list(account.remoteKey(),normalized);
        UUID providerId=repository.findStorageProvider(id).orElse(null);
        if(scanMode==ScanMode.STORAGE) {
            if(providerId==null) throw new IllegalStateException("drive storage provider is not bound");
            return storageConnector.list(providerId.toString(),normalized);
        }
        List<IndexItem> legacy=legacyConnector.list(account.remoteKey(),normalized);
        if(providerId==null) {
            LOGGER.warn("Drive storage shadow skipped because provider is not bound: accountId={}",id);
            return legacy;
        }
        try {
            List<IndexItem> shadow=storageConnector.list(providerId.toString(),normalized);
            if(!signatures(legacy).equals(signatures(shadow)))
                LOGGER.warn("Drive storage shadow mismatch: accountId={}, path={}",id,normalized);
        } catch(RuntimeException exception) {
            LOGGER.warn("Drive storage shadow failed: accountId={}, path={}",id,normalized,exception);
        }
        return legacy;
    }
    /** 绑定 Storage Gateway Provider。 @param id 账户 @param providerId Provider 标识 */
    public void bindStorageProvider(UUID id,UUID providerId) {
        require(id); transactions.executeWithoutResult(status -> repository.bindStorageProvider(id,providerId));
    }
    /** 结束未完成索引运行。 @param id 账户 @param runId 运行 @param status 终态 */
    public void finishRun(UUID id,UUID runId,String status) {
        require(id);
        if(!java.util.Set.of("FAILED","TIMED_OUT","CANCELLED").contains(status))
            throw new IllegalArgumentException("drive index terminal status is invalid");
        transactions.executeWithoutResult(s -> repository.finishRun(id,runId,status));
    }
    /** 创建账户索引刷新任务。 @param accountId 账户标识 @param ownerId 所有者 @param request 请求 @return 操作 */
    public OperationView refreshIndex(UUID accountId,long ownerId,RefreshIndexRequest request) {
        requireOwner(accountId,ownerId);
        String scopedKey=scopedIdempotencyKey(accountId,request.idempotencyKey());
        OperationView existing=repository.findOperationByIdempotencyKey(scopedKey).orElse(null);
        if(existing!=null) {
            if(!existing.accountId().equals(accountId)) throw new IllegalStateException("drive operation idempotency conflict");
            return reconcile(existing);
        }
        UUID operationId=UUID.nameUUIDFromBytes((accountId+"\u0000"+request.idempotencyKey())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID taskId=schedulerClient.createIndexTask(operationId,accountId,scopedKey);
        return transactions.execute(status -> repository.saveIndexOperation(operationId,accountId,taskId,scopedKey));
    }
    /**
     * 创建跨 Drive 账户的受控对象复制任务。
     *
     * @param sourceAccountId 来源账户
     * @param ownerId 所有者
     * @param request 复制请求
     * @return Drive 操作
     */
    public OperationView copyObject(UUID sourceAccountId, long ownerId, CopyObjectRequest request) {
        CopyContext context = requireCopyContext(sourceAccountId, ownerId, request.targetAccountId());
        String sourcePath = normalizeRequired(request.sourcePath());
        String targetPath = normalizeRequired(request.targetPath());
        String scopedKey = scopedCopyKey("object", context.source().id(), context.target().id(),
                request.idempotencyKey());
        StorageOperationView storage = storageConnector.copyObject(scopedKey, context.sourceProvider(), sourcePath,
                context.targetProvider(), targetPath);
        return saveStorageOperation(storage, context.source().id(), scopedKey);
    }
    /**
     * 创建跨 Drive 账户的受控递归树复制任务。
     *
     * @param sourceAccountId 来源账户
     * @param ownerId 所有者
     * @param request 复制请求
     * @return Drive 操作
     */
    public OperationView copyTree(UUID sourceAccountId, long ownerId, CopyTreeRequest request) {
        CopyContext context = requireCopyContext(sourceAccountId, ownerId, request.targetAccountId());
        String sourcePath = normalize(request.sourcePath());
        String targetPath = normalize(request.targetPath());
        String scopedKey = scopedCopyKey("tree", context.source().id(), context.target().id(),
                request.idempotencyKey());
        StorageOperationView storage = storageConnector.copyTree(scopedKey, context.sourceProvider(), sourcePath,
                context.targetProvider(), targetPath, request.maximumObjects());
        return saveStorageOperation(storage, context.source().id(), scopedKey);
    }
    /**
     * 创建跨 Drive 账户的受控递归移动任务。
     *
     * @param sourceAccountId 来源账户
     * @param ownerId 所有者
     * @param request 移动请求
     * @return Drive 操作
     */
    public OperationView moveTree(UUID sourceAccountId, long ownerId, MoveTreeRequest request) {
        CopyContext context = requireCopyContext(sourceAccountId, ownerId, request.targetAccountId());
        String sourcePath = normalizeRequired(request.sourcePath());
        String targetPath = normalizeRequired(request.targetPath());
        String scopedKey = scopedCopyKey("move", context.source().id(), context.target().id(),
                request.idempotencyKey());
        StorageOperationView storage = storageConnector.moveTree(scopedKey, context.sourceProvider(), sourcePath,
                context.targetProvider(), targetPath, request.maximumObjects());
        return saveStorageOperation(storage, context.source().id(), scopedKey);
    }
    /**
     * 创建 Drive 账户内的受控非根目录树删除任务。
     *
     * @param accountId 账户
     * @param ownerId 所有者
     * @param request 删除请求
     * @return Drive 操作
     */
    public OperationView deleteTree(UUID accountId, long ownerId, DeleteTreeRequest request) {
        AccountView account = requireOwner(accountId, ownerId);
        if (!account.enabled()) throw new IllegalArgumentException("drive account is disabled");
        if (account.readOnly()) throw new IllegalArgumentException("drive account is read only");
        UUID provider = repository.findStorageProvider(account.id())
                .orElseThrow(() -> new IllegalStateException("drive storage provider is not bound"));
        String path = normalizeRequired(request.path());
        String scopedKey = scopedIdempotencyKey(account.id(), "delete:" + request.idempotencyKey())
                .replace("drive-index:", "drive-delete:");
        StorageOperationView storage = storageConnector.deleteTree(scopedKey, provider, path,
                request.maximumObjects());
        return saveStorageOperation(storage, account.id(), scopedKey);
    }
    /** 查询账户操作。 @param operationId 操作标识 @param ownerId 所有者 @return 操作 */
    public OperationView getOperation(UUID operationId,long ownerId) {
        OperationView operation=repository.findOperation(operationId)
            .orElseThrow(() -> new IllegalArgumentException("drive operation not found"));
        requireOwner(operation.accountId(),ownerId);
        return isStorageManaged(operation) ? reconcileStorage(operation) : reconcile(operation);
    }
    /** 取消账户操作。 @param operationId 操作标识 @param ownerId 所有者 @return 操作 */
    public OperationView cancelOperation(UUID operationId,long ownerId) {
        OperationView operation=getOperation(operationId,ownerId);
        if(!java.util.Set.of("SUCCEEDED","FAILED","TIMED_OUT","CANCELLED").contains(operation.status())) {
            if (isStorageManaged(operation)) storageConnector.cancel(operation.id());
            else schedulerClient.cancel(operation.taskInstanceId());
        }
        return isStorageManaged(operation) ? reconcileStorage(operation) : reconcile(operation);
    }
    private OperationView reconcile(OperationView operation) {
        String status=schedulerClient.getStatus(operation.taskInstanceId());
        return status.equals(operation.status())?operation:
            transactions.execute(transaction -> repository.updateOperationStatus(operation.id(),status));
    }
    private OperationView reconcileStorage(OperationView operation) {
        StorageOperationView storage = storageConnector.operation(operation.id());
        return storage.status().equals(operation.status())
                && java.util.Objects.equals(storage.errorCode(), operation.errorCode()) ? operation
                : transactions.execute(transaction -> repository.updateOperationStatus(
                        operation.id(), storage.status(), storage.errorCode()));
    }
    private AccountView requireOwner(UUID id,long ownerId) {
        AccountView account=require(id);
        if(account.ownerId()!=ownerId) throw new IllegalArgumentException("drive account not found");
        return account;
    }
    private String scopedIdempotencyKey(UUID accountId,String key) {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "drive-index:"+accountId+":"+java.util.HexFormat.of().formatHex(digest);
        } catch(java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable",exception);
        }
    }
    private String scopedCopyKey(String kind, UUID sourceAccountId, UUID targetAccountId, String key) {
        String material = "object".equals(kind) ? targetAccountId + ":" + key
                : kind + ":" + targetAccountId + ":" + key;
        String prefix = "object".equals(kind) ? "drive-copy:" : "drive-copy-" + kind + ":";
        return scopedIdempotencyKey(sourceAccountId, material).replace("drive-index:", prefix);
    }
    private CopyContext requireCopyContext(UUID sourceAccountId, long ownerId, UUID targetAccountId) {
        AccountView source = requireOwner(sourceAccountId, ownerId);
        AccountView target = requireOwner(targetAccountId, ownerId);
        if (!source.enabled() || !target.enabled()) throw new IllegalArgumentException("drive account is disabled");
        if (target.readOnly()) throw new IllegalArgumentException("drive target account is read only");
        UUID sourceProvider = repository.findStorageProvider(source.id())
                .orElseThrow(() -> new IllegalStateException("drive storage provider is not bound"));
        UUID targetProvider = repository.findStorageProvider(target.id())
                .orElseThrow(() -> new IllegalStateException("drive storage provider is not bound"));
        return new CopyContext(source, target, sourceProvider, targetProvider);
    }
    private OperationView saveStorageOperation(StorageOperationView storage, UUID sourceAccountId, String scopedKey) {
        return transactions.execute(status -> repository.saveStorageOperation(storage, sourceAccountId, scopedKey));
    }
    private boolean isStorageManaged(OperationView operation) {
        return java.util.Set.of("COPY_OBJECT", "COPY_TREE_NATIVE", "MOVE_TREE", "DELETE_TREE")
                .contains(operation.operationType());
    }
    private AccountView require(UUID id) { return repository.findAccount(id).orElseThrow(() -> new IllegalArgumentException("drive account not found")); }
    private String normalize(String path) {
        String value=path==null?"":path.trim().replace('\\','/');
        while(value.startsWith("/")) value=value.substring(1);
        if(value.length()>2048 || java.util.Arrays.asList(value.split("/",-1)).contains(".."))
            throw new IllegalArgumentException("drive path is invalid");
        return value;
    }
    private String normalizeRequired(String path) {
        String normalized = normalize(path);
        if (normalized.isBlank()) throw new IllegalArgumentException("drive path is invalid");
        return normalized;
    }
    private String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }
    private java.util.Set<String> signatures(List<IndexItem> items) {
        java.util.Set<String> values=new java.util.TreeSet<>();
        for(IndexItem item:items) values.add(item.remotePath()+"\u0000"+item.displayName()+"\u0000"+item.sizeBytes()
            +"\u0000"+item.directory()+"\u0000"+item.modifiedAt()+"\u0000"+item.contentSha256());
        return values;
    }
    private enum ScanMode { LEGACY, DUAL, STORAGE }
    private record CopyContext(AccountView source, AccountView target, UUID sourceProvider, UUID targetProvider) { }
}

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
    /** 创建领域服务。 @param repository 仓储 @param transactions 事务模板 */
    public DriveService(DriveRepository repository, TransactionTemplate transactions, RcloneConnector legacyConnector,
                        StorageGatewayConnector storageConnector,
                        @Value("${drive.storage-scan-mode:LEGACY}") String scanMode) {
        this.repository=repository; this.transactions=transactions; this.legacyConnector=legacyConnector;
        this.storageConnector=storageConnector; this.scanMode=ScanMode.valueOf(scanMode.toUpperCase(java.util.Locale.ROOT));
    }
    /** 登记账户。 @param request 请求 @return 账户 */
    public AccountView register(RegisterAccountRequest request) { return transactions.execute(s -> repository.register(request)); }
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
    private AccountView require(UUID id) { return repository.findAccount(id).orElseThrow(() -> new IllegalArgumentException("drive account not found")); }
    private String normalize(String path) {
        String value=path==null?"":path.trim().replace('\\','/');
        while(value.startsWith("/")) value=value.substring(1);
        if(value.length()>2048 || value.contains(":") || java.util.Arrays.asList(value.split("/",-1)).contains(".."))
            throw new IllegalArgumentException("drive path is invalid");
        return value;
    }
    private java.util.Set<String> signatures(List<IndexItem> items) {
        java.util.Set<String> values=new java.util.TreeSet<>();
        for(IndexItem item:items) values.add(item.remotePath()+"\u0000"+item.displayName()+"\u0000"+item.sizeBytes()
            +"\u0000"+item.directory()+"\u0000"+item.modifiedAt()+"\u0000"+item.contentSha256());
        return values;
    }
    private enum ScanMode { LEGACY, DUAL, STORAGE }
}

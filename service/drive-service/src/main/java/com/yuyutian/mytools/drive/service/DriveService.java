package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import com.yuyutian.mytools.drive.repository.DriveRepository;
import com.yuyutian.mytools.drive.connector.RcloneConnector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;

/** Drive 领域服务。 */
@Service
public class DriveService {
    private final DriveRepository repository; private final TransactionTemplate transactions; private final RcloneConnector connector;
    /** 创建领域服务。 @param repository 仓储 @param transactions 事务模板 */
    public DriveService(DriveRepository repository, TransactionTemplate transactions, RcloneConnector connector) {
        this.repository=repository; this.transactions=transactions; this.connector=connector;
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
    public List<IndexItem> scan(UUID id,String path) { AccountView account=require(id); return connector.list(account.remoteKey(),normalize(path)); }
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
}

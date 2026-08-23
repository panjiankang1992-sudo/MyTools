package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import com.yuyutian.mytools.drive.repository.DriveRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;

/** Drive 领域服务。 */
@Service
public class DriveService {
    private final DriveRepository repository; private final TransactionTemplate transactions;
    /** 创建领域服务。 @param repository 仓储 @param transactions 事务模板 */
    public DriveService(DriveRepository repository, TransactionTemplate transactions) {
        this.repository=repository; this.transactions=transactions;
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
    private AccountView require(UUID id) { return repository.findAccount(id).orElseThrow(() -> new IllegalArgumentException("drive account not found")); }
    private String normalize(String path) {
        String value=path==null?"":path.trim().replace('\\','/');
        while(value.startsWith("/")) value=value.substring(1);
        if(value.length()>2048 || value.contains(":") || java.util.Arrays.asList(value.split("/",-1)).contains(".."))
            throw new IllegalArgumentException("drive path is invalid");
        return value;
    }
}

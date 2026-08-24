package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveModels.*;
import com.yuyutian.mytools.drive.repository.DriveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Drive 索引事务测试。 */
@SpringBootTest
class DriveServiceTest {
    @Autowired private DriveService service;
    @Autowired private DriveRepository repository;
    @MockBean private DriveTaskSchedulerClient schedulerClient;

    @Test
    void shouldResumeBatchesAndDeleteStaleItemsOnlyAfterCompletion() {
        AccountView account=service.register(new RegisterAccountRequest(7L,"legacy-1","Primary","RCLONE",
            "secret://drive/legacy-1","primary",true,true));
        UUID firstRun=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-1","cursor-1",false,List.of(
            item("old.txt","",3))));
        IndexBatchView duplicate=service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-1","cursor-1",false,List.of(
            item("old.txt","",3))));
        assertThat(duplicate.acceptedItems()).isZero();
        service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-2",null,true,List.of(item("keep.txt","",4))));
        assertThat(service.ingest(account.id(),new IndexBatchRequest(firstRun,"batch-1","cursor-1",false,List.of(
            item("old.txt","",3)))).acceptedItems()).isZero();
        assertThat(service.list(account.id(),7L," ")).extracting(ItemView::displayName).containsExactly("keep.txt","old.txt");

        UUID secondRun=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(secondRun,"batch-1",null,true,List.of(item("keep.txt","",5))));
        assertThat(service.list(account.id(),7L,"")).extracting(ItemView::displayName).containsExactly("keep.txt");
        assertThatThrownBy(() -> service.list(account.id(),8L,"")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowNewRunAfterFailureHook() {
        AccountView account=service.register(new RegisterAccountRequest(9L,"legacy-2","Backup","RCLONE",
            "secret://drive/legacy-2","backup",true,true));
        UUID failedRun=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(failedRun,"batch-1",null,false,List.of(item("partial.txt","",1))));
        service.finishRun(account.id(),failedRun,"FAILED");
        UUID recoveryRun=UUID.randomUUID();
        assertThat(service.ingest(account.id(),new IndexBatchRequest(recoveryRun,"complete",null,true,List.of()))
            .status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldExposeOnlySanitizedStorageMigrationFields() {
        AccountView account=service.register(new RegisterAccountRequest(11L,"external-private","Private Display","RCLONE",
            "secret://drive/migration-safe","migration_safe",true,true));

        StorageMigrationAccount migrated=repository.listStorageMigrationAccounts(null,500).items().stream()
            .filter(item -> item.id().equals(account.id())).findFirst().orElseThrow();

        assertThat(migrated.remoteKey()).isEqualTo("migration_safe");
        assertThat(migrated.providerSecretRef()).isEqualTo("secret://drive/migration-safe");
        assertThat(migrated.toString()).doesNotContain("Private Display","external-private");
    }

    @Test
    void shouldProduceTheSharedReconciliationGoldenDigest() {
        AccountView account=service.register(new RegisterAccountRequest(12L,"digest-account","Digest","RCLONE",
            "secret://drive/digest","digest_remote",true,true));
        UUID runId=UUID.randomUUID();
        service.ingest(account.id(),new IndexBatchRequest(runId,"complete",null,true,List.of(item("a.txt","",3))));

        assertThat(repository.indexDigest(account.id()).contentSha256())
            .isEqualTo("8501ff9beb116985f2ad48e3e4417e85c1f0121b8498a344a7fb307b51314879");
    }

    @Test
    void shouldListOnlyAccountsOwnedByTheRequestedOwner() {
        AccountView first=service.register(new RegisterAccountRequest(21L,"owned-a","A Drive","RCLONE",
            "secret://drive/owned-a","owned_a",true,true));
        AccountView second=service.register(new RegisterAccountRequest(22L,"owned-b","B Drive","RCLONE",
            "secret://drive/owned-b","owned_b",true,true));

        assertThat(service.listAccounts(21L)).extracting(AccountView::id).contains(first.id()).doesNotContain(second.id());
    }

    @Test
    void shouldCreateQueryAndCancelOwnerBoundIndexOperation() {
        AccountView account=service.register(new RegisterAccountRequest(13L,"refresh-account","Refresh","RCLONE",
            "secret://drive/refresh","refresh_remote",true,true));
        UUID taskId=UUID.randomUUID();
        when(schedulerClient.createIndexTask(any(),eq(account.id()),startsWith("drive-index:"))).thenReturn(taskId);
        when(schedulerClient.getStatus(taskId)).thenReturn("RUNNING","RUNNING","CANCELLING");

        OperationView created=service.refreshIndex(account.id(),13L,new RefreshIndexRequest("refresh-1"));
        OperationView replayed=service.refreshIndex(account.id(),13L,new RefreshIndexRequest("refresh-1"));
        assertThat(created.id()).isEqualTo(replayed.id());
        assertThat(service.cancelOperation(created.id(),13L).status()).isEqualTo("CANCELLING");
        assertThatThrownBy(() -> service.getOperation(created.id(),14L)).isInstanceOf(IllegalArgumentException.class);
        verify(schedulerClient,times(1)).createIndexTask(any(),eq(account.id()),startsWith("drive-index:"));
        verify(schedulerClient).cancel(taskId);
    }

    private IndexItem item(String path,String parent,long size) {
        return new IndexItem(path,path,parent,path,"text/plain",size,false,Instant.parse("2026-01-01T00:00:00Z"),null);
    }
}
